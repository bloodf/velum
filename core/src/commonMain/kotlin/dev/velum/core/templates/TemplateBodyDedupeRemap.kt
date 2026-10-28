package dev.velum.core.templates

/**
 * Removed exact-duplicate body ids → retained survivor ids (#99).
 *
 * Mirrors `docs/templates/body_dedupe_remap.json` /
 * classpath `template_body_dedupe_remap.json` after
 * `tools/templates/body_dedupe.py`. Used to reconcile existing installs that
 * already imported the pre-dedupe full catalog: remap AutoFlow / summary-version
 * references, then delete the dropped builtin rows. User-created templates
 * (`builtin = 0`) are never deleted.
 */
object TemplateBodyDedupeRemap {
    const val META_KEY: String = "template_body_dedupe_v1"

    val OldToSurvivor: Map<String, String> = mapOf(
        "plaud.1-0ecf3c6cd4" to "plaud.1-6b50b331ee",
        "plaud.1-157b3b18f0" to "plaud.1-c1389dcddb",
        "plaud.1-3457c1fbb5" to "plaud.1-39046c83ad",
        "plaud.1-43672d9ef5" to "plaud.1-8608a14c23",
        "plaud.1-8e017ccc32" to "plaud.1-cc0fb2a442",
        "plaud.1-ae06989e42" to "plaud.1-d429eb8e26",
        "plaud.1-b7244a4542" to "plaud.1-e665180aab",
        "plaud.1-c1743085bb" to "plaud.1-6a1f237bb9",
        "plaud.1-fe2c7df7c1" to "plaud.1-1fc6bfa1df",
        "plaud.10-056a5292d8" to "plaud.11-edb4f97867",
        "plaud.10-0c75475cbe" to "plaud.11-6a85f973db",
        "plaud.10-3f89b24841" to "plaud.11-2c89fcf28b",
        "plaud.10-8c56fdecb9" to "plaud.10-68e34e72b4",
        "plaud.10-dddba954f2" to "plaud.11-7bbaa4ac17",
        "plaud.2-0abbe29793" to "plaud.3-523b8bc243",
        "plaud.2-ee9fd0d1a5" to "plaud.3-d65616caca",
        "plaud.29-6d16208625" to "plaud.29-b88c0b165b",
        "plaud.29-bf0aa16cae" to "plaud.29-6e98c2ff08",
        "plaud.3-3bdd728728" to "plaud.2-9eb7114059",
        "plaud.3-ba7dc83426" to "plaud.2-92e1370283",
        "plaud.30-c3dc113870" to "plaud.29-2060ecbdfb",
        "plaud.44-2f523d98c0" to "plaud.44-069d9fcb3e",
        "plaud.44-aec5f929cc" to "plaud.45-b80f4da7cb",
        "plaud.45-126dc4b2af" to "plaud.44-ebb52bc89d",
        "plaud.92-c558406232" to "plaud.93-8868dddd73",
    )
}
