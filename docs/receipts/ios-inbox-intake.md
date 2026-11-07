# iOS Inbox intake source receipt

Scope: epic #46. Receives only local regular files directly in Documents/Inbox. Swift calls the exported IosIncomingShare singleton. No provider access, network, new permission, opening in place, or UI file sharing.

Parents: PR534 cd862e1f and immutable PR508 c6eff2072dcf026198261cb0852b1949e77b1c2c are retained in ancestry. Their qualification and ordered merge prerequisites remain open. PR532 is not used. Do not merge this stack transitively. PR351 veto remains.

Apple documentation: CFBundleDocumentTypes declares public.audio with Viewer role and Alternate rank. LSSupportsOpeningDocumentsInPlace=false requests copies instead of original provider documents.
- https://developer.apple.com/documentation/bundleresources/information-property-list/cfbundledocumenttypes
- https://developer.apple.com/documentation/bundleresources/information-property-list/lssupportsopeningdocumentsinplace

The queue stores at most eight pending cache copies and 64 delivery identities. Each copy has an inclusive 1 GiB limit checked against descriptor size and actual streamed bytes, a 64 KiB buffer, exclusive destination creation, and fsync before publication. Pending records and attempt counts are persisted in preferences. Cache eviction may make recovery report CopyFailed. Source Inbox files remain untouched. Registered copies use the existing imports root and cleanup path. Recovery requires a new PR508 consent decision; no intake call starts import or cloud work. Generation checks reject work scheduled before clear; a stale approval fails its required pending-record attempt check. Three started attempts retire a recovered share.

Checks performed here: plist lint PASS, git diff --check PASS, Gradle task discovery PASS. No Kotlin lint task is configured. CodeGraph sync/status attempted before and after edits: index uninitialized; bounded source fallback used. Focused iOS fixture tests were added, but execution awaits the parent's official Mac/Cortex lane. Exact-head objective independent review awaits the parent. Source is not runtime PASS. No model, inference, UI, device, or standalone native build campaign was run.

Route lease: 2ae3035a-8281-4086-a23b-1a95d0388808. Parent native launch metadata is authoritative. No merge grant has been issued.
