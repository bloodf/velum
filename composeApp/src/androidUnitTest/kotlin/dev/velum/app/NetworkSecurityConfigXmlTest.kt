package dev.velum.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import org.xmlpull.v1.XmlPullParser

/**
 * Regression for SM-S948B launch crash:
 * `Nested domain-config not allowed in debug-overrides`.
 *
 * Android's XmlConfigSource only allows trust-anchors under debug-overrides.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NetworkSecurityConfigXmlTest {

    @Test
    fun debugOverridesContainOnlyTrustAnchors() {
        val root = parseConfig()
        val overrides = root.getElementsByTagName("debug-overrides")
        assertEquals("expected a single debug-overrides block", 1, overrides.length)
        val debug = overrides.item(0) as Element
        val children = debug.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
            assertEquals(
                "debug-overrides may only contain trust-anchors (got ${node.nodeName})",
                "trust-anchors",
                node.nodeName,
            )
        }
        assertFalse(
            "domain-config must not nest under debug-overrides",
            debug.getElementsByTagName("domain-config").length > 0,
        )
    }

    @Test
    fun loopbackCleartextAllowedAtTopLevel() {
        val root = parseConfig()
        val domains = root.getElementsByTagName("domain")
        val names = mutableSetOf<String>()
        for (i in 0 until domains.length) {
            names += domains.item(i).textContent.trim()
        }
        assertTrue("localhost missing: $names", "localhost" in names)
        assertTrue("127.0.0.1 missing: $names", "127.0.0.1" in names)
        assertTrue("10.0.2.2 missing: $names", "10.0.2.2" in names)

        val domainConfigs = root.getElementsByTagName("domain-config")
        assertTrue("expected top-level domain-config", domainConfigs.length >= 1)
        for (i in 0 until domainConfigs.length) {
            val cfg = domainConfigs.item(i) as Element
            assertEquals(
                "domain-config parent must be network-security-config",
                "network-security-config",
                cfg.parentNode.nodeName,
            )
            assertEquals("true", cfg.getAttribute("cleartextTrafficPermitted"))
        }
    }

    @Test
    fun baseConfigDisablesCleartext() {
        val root = parseConfig()
        val base = root.getElementsByTagName("base-config").item(0) as Element
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))
    }

    @Test
    fun packagedResourceResolves() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resId = context.resources.getIdentifier(
            "network_security_config",
            "xml",
            context.packageName,
        )
        assertTrue("network_security_config resource missing", resId != 0)
        // Opening the XML resource exercises Android's binary XML path used at Application start.
        val parser = context.resources.getXml(resId)
        try {
            var sawRoot = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG &&
                    parser.name == "network-security-config"
                ) {
                    sawRoot = true
                }
                if (event == XmlPullParser.START_TAG &&
                    parser.name == "domain-config"
                ) {
                    // domain-config must sit directly under root (depth 2).
                    assertEquals(
                        "domain-config must be a direct child of network-security-config",
                        2,
                        parser.depth,
                    )
                }
                if (event == XmlPullParser.START_TAG &&
                    parser.name == "debug-overrides"
                ) {
                    val overridesDepth = parser.depth
                    event = parser.next()
                    while (!(event == XmlPullParser.END_TAG &&
                            parser.name == "debug-overrides" &&
                            parser.depth == overridesDepth)
                    ) {
                        // Only direct children of debug-overrides are constrained.
                        if (event == XmlPullParser.START_TAG &&
                            parser.depth == overridesDepth + 1
                        ) {
                            assertEquals(
                                "debug-overrides children must be trust-anchors",
                                "trust-anchors",
                                parser.name,
                            )
                        }
                        event = parser.next()
                    }
                }
                event = parser.next()
            }
            assertTrue(sawRoot)
        } finally {
            parser.close()
        }
        assertNotNull(context.applicationInfo)
    }

    private fun parseConfig(): Element {
        val file = configFile()
        assertTrue("missing $file", file.isFile)
        val doc = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
        return doc.documentElement
    }

    private fun configFile(): File {
        // Resolve from module root whether Gradle cwd is repo root or composeApp/.
        val candidates = listOf(
            File("src/androidMain/res/xml/network_security_config.xml"),
            File("composeApp/src/androidMain/res/xml/network_security_config.xml"),
            File("../composeApp/src/androidMain/res/xml/network_security_config.xml"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("network_security_config.xml not found from cwd=${File(".").absolutePath}")
    }
}
