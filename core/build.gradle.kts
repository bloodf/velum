plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    jvm()
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            api(project(":plaud-protocol"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.jvm)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native)
        }
    }
}

android {
    namespace = "dev.velum.core"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // KMP commonMain/resources are not always merged into the Android classpath.
    // Pin them on the AGP main source set so classLoader.getResourceAsStream works
    // for PlaudTemplateAsset / AutoFlowTemplateAsset / PlaudFullTemplateAsset.
    sourceSets.getByName("main").resources.srcDir("src/commonMain/resources")
}

sqldelight {
    databases {
        create("VelumDatabase") {
            packageName.set("dev.velum.core.db")
            // Schema version is 4 (Velum.sq + migrations/1.sqm + 2.sqm + 3.sqm). Next change needs migrations/4.sqm.
            // See src/commonMain/sqldelight/dev/velum/core/db/migrations/README.md
            migrationOutputDirectory.set(layout.buildDirectory.dir("generated/sqldelight/migrations"))
            verifyMigrations.set(true)
        }
    }
}

/**
 * Validate + pack `templates/autoflow/**/*.json` into classpath JSONL.
 * Also runs automatically before JVM unit tests so CI catches bad contributions.
 */
val validateAutoflowTemplates = tasks.register<Exec>("validateAutoflowTemplates") {
    group = "verification"
    description = "Validate AutoFlow automation templates and sync classpath JSONL"
    workingDir = rootProject.projectDir
    commandLine("bash", "scripts/sync-autoflow-templates.sh")
    inputs.dir(rootProject.file("templates/autoflow"))
    inputs.file(rootProject.file("scripts/sync-autoflow-templates.sh"))
    outputs.file(layout.projectDirectory.file("src/commonMain/resources/autoflow_templates.jsonl"))
}

// Pack JSONL before resources are copied for any target that ships the catalog.
// AGP uses process*JavaRes; KMP/JVM uses *ProcessResources.
tasks.matching {
    val n = it.name
    n.endsWith("ProcessResources") ||
        n.endsWith("JavaRes") ||
        n.contains("process") && n.contains("JavaRes", ignoreCase = true) ||
        n == "jvmTest"
}.configureEach {
    dependsOn(validateAutoflowTemplates)
}
