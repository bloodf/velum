import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    jvm()
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        // Public libarchive is not part of the iOS SDK. Embed our pinned static build in the klib.
        val archiveRoot = layout.buildDirectory.dir("libarchive/${target.name}")
        val buildArchive = tasks.register("buildModelZip${target.name.replaceFirstChar { it.uppercase() }}") {
            inputs.files("src/nativeInterop/libarchive/raw-path.patch", "src/nativeInterop/libarchive/COPYING")
            outputs.dir(archiveRoot)
            doLast {
                val root = archiveRoot.get().asFile
                root.mkdirs()
                val sourceArchive = root.resolve("libarchive-3.8.11.tar.gz")
                java.net.URI("https://github.com/libarchive/libarchive/releases/download/v3.8.11/libarchive-3.8.11.tar.gz")
                    .toURL().openStream().use { input -> sourceArchive.outputStream().use { input.copyTo(it) } }
                val digest = MessageDigest.getInstance("SHA-256")
                sourceArchive.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                check(digest.digest().joinToString("") { "%02x".format(it) } ==
                    "900fe9fc7237c2b04f090f3c3648de8fe597525771f5035016537ce2d667d12b") {
                    "libarchive source checksum mismatch"
                }
                copy { from(tarTree(resources.gzip(sourceArchive))); into(root) }
                val source = root.resolve("libarchive-3.8.11")
                exec {
                    workingDir(source)
                    commandLine("/usr/bin/patch", "-p1", "-i", project.file("src/nativeInterop/libarchive/raw-path.patch").absolutePath)
                }
                val sdk = if (target.name == "iosArm64") "iphoneos" else "iphonesimulator"
                val sdkPath = java.io.ByteArrayOutputStream()
                exec { commandLine("xcrun", "--sdk", sdk, "--show-sdk-path"); standardOutput = sdkPath }
                val sysroot = sdkPath.toString().trim()
                val arch = if (target.name == "iosX64") "x86_64" else "arm64"
                exec {
                    commandLine(listOf("cmake", "-S", source.absolutePath, "-B", root.resolve("build").absolutePath,
                        "-DCMAKE_SYSTEM_NAME=iOS", "-DCMAKE_OSX_SYSROOT=$sysroot", "-DCMAKE_OSX_ARCHITECTURES=$arch",
                        "-DCMAKE_OSX_DEPLOYMENT_TARGET=15.0", "-DCMAKE_BUILD_TYPE=Release", "-DBUILD_SHARED_LIBS=OFF",
                        "-DZLIB_LIBRARY=$sysroot/usr/lib/libz.tbd", "-DZLIB_INCLUDE_DIR=$sysroot/usr/include",
                        "-DENABLE_ZLIB=ON", "-DDONT_FAIL_ON_CRC_ERROR=OFF") +
                        listOf("OPENSSL", "MBEDTLS", "NETTLE", "MD", "LIBB2", "LZ4", "LZO", "LZMA", "ZSTD",
                            "BZip2", "LIBXML2", "BSDXML", "EXPAT", "PCREPOSIX", "PCRE2POSIX", "CNG", "ICONV",
                            "TAR", "CPIO", "CAT", "UNZIP", "TEST", "XATTR", "ACL").map { "-DENABLE_$it=OFF" })
                }
                exec { commandLine("cmake", "--build", root.resolve("build").absolutePath, "--target", "archive_static", "--parallel", "2") }
            }
        }
        target.compilations.getByName("main").cinterops.create("modelZip") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/modelZip.def"))
            includeDirs(archiveRoot.map { it.dir("libarchive-3.8.11/libarchive") })
            extraOpts("-libraryPath", archiveRoot.get().dir("build/libarchive").asFile.absolutePath)
            tasks.named(interopProcessingTaskName).configure { dependsOn(buildArchive) }
        }
        target.compilations.getByName("main").cinterops.create("modelCompression") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/modelCompression.def"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        val jvmTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
                implementation(libs.sqldelight.jvm)
                implementation(libs.sqldelight.runtime)
                implementation(project(":ble"))
                implementation(project(":ble-testing"))
                implementation(project(":plaud-protocol"))
            }
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sherpa.onnx.android)
            implementation(libs.kopus)
            implementation(libs.commons.compress)
            implementation(libs.litertlm.android)
            implementation(libs.androidx.work.runtime)
            implementation("androidx.core:core-ktx:1.15.0")
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.kopus)
            implementation(libs.commons.compress)
        }
    }
}

android {
    namespace = "dev.velum.ai"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        ndk {
            // Needle/Whistle JNI (libvelum_needle) + libc++_shared; x86 emulators use Whisper Tiny.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }
    sourceSets.getByName("main").jniLibs.srcDir("src/androidMain/jniLibs")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs {
            // Uncompressed + page-aligned so AGP/zipalign can enforce 16 KB packing.
            useLegacyPackaging = false
            // Ship Needle + libc++_shared from this module into consuming APKs.
            keepDebugSymbols += listOf("**/libvelum_needle.so")
        }
    }
}
