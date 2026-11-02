# Import from Plaud (one-time identity + optional templates)

Velum never contacts Plaud servers. Device identity (`snSignature`, bind token, optional RSA PEMs) must be imported once from the owner’s logged-in Plaud app (storage/export). See `docs/APK_ANALYSIS.md`.

## JSON export schema (version 1)

```json
{
  "version": 1,
  "source": "plaud_app_export",
  "device": {
    "serial": "881…",
    "snSignatureBase64": "<base64 of exactly 256 bytes>",
    "bindToken": "<user_id / bind token>",
    "publicKeyPem": "-----BEGIN PUBLIC KEY-----… (optional)",
    "privateKeyPem": "-----BEGIN PRIVATE KEY-----… (optional)"
  },
  "templates": [
    {
      "id": "optional-source-id",
      "name": "My custom",
      "category": "meeting",
      "description": "optional",
      "systemPrompt": "…",
      "prompt": "alias of systemPrompt"
    }
  ]
}
```

- `snSignatureBase64` must decode to **256 bytes** (FE10).
- Templates are stored as non-builtin rows with id prefix `plaud-import-`.
- Future emulator dumps (templates catalog, custom templates, identity) should use this file.

## UI

Settings → **Import from Plaud**: paste fields and/or choose a JSON file (Android SAF). Material is stored via `SecureStore` (Android Keystore).

Identity fields and the JSON draft are masked in the editor. Validation errors use controlled messages; decoder diagnostics, raw input and unexpected exception messages are never displayed. Identity objects also have redacted diagnostic representations. Do not paste real bundles into issues, chat, logs or test artifacts; fixtures must be synthetic.

Malformed or partial bundles are rejected before writing identity or templates. Identity updates use one versioned secure-store value (`device.identity_bundle_v1`) so readers observe a complete identity. Existing per-field entries remain readable and unchanged; reading does not migrate them. A new successful import takes precedence over legacy fields. An unreadable active bundle never silently selects an old identity. Explicit clear removes legacy fields before the active bundle.

Legacy per-field entries are retained after a new import by design: they stay encrypted in the vault and are never read while an active bundle exists. Only an explicit clear removes them. Consequence: a downgrade to a build that predates the bundle reads the older legacy identity, which is internally consistent but stale. The stored bundle uses pinned field names and format version 1; a future format must use a new key rather than reuse `device.identity_bundle_v1` with another version value. An unreadable or unsupported active bundle currently surfaces as incomplete keys (re-import or clear), not as a distinct error.

Secure-storage failures remain explicit and retryable; cancellation propagates before commit. A provider can report an uncertain commit after storing a complete new bundle, so failure does not prove that the old identity is still active. Cancellation can likewise be reported after the commit has completed. Concurrent imports and clears are not serialized and resolve as last-writer-wins. Identity and template updates are not one transaction: a later template failure can follow a complete identity import. Real-device unlock and owner persistence checks remain separate (#3 / #4).

## Synthetic Android persistence verification

Build the isolated test APKs, then choose an explicit emulator and a new empty evidence directory:

```sh
./gradlew :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest -PvelumIdentityPersistenceTest=true --max-workers=2
./gradlew :composeApp:assembleMinifiedTest :composeApp:assembleMinifiedTestAndroidTest -PvelumIdentityPersistenceTest=true -PvelumTestBuildType=minifiedTest --max-workers=2
python3 scripts/check-identity-persistence.py --serial emulator-5554 --output /tmp/velum-identity-check-001
```

Use the JDK/SDK setup in [ENGINEERING_STANDARDS.md](ENGINEERING_STANDARDS.md). The script rejects physical targets and dirty verification trees. Its test-only runner creates a plain `Application`, avoiding `VelumApplication`/`AppContainer` startup. Both JSON and pasted-field APIs use fresh UUID-namespaced preferences and Android Keystore aliases; production vault names are never used. The prepare and verify phases run in different PIDs with different process start times. Verification checks reloaded identity, encrypted preference contents, app UID/GID ownership, private-directory isolation, malformed-input preservation and process-scoped log redaction. Android `MODE_PRIVATE` may use `0660` for the app's own UID/GID; it must not grant other-user access.

The permission assertion follows Android's [ContextImpl implementation](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-14.0.0_r1/core/java/android/app/ContextImpl.java#L3279), rather than assuming that private preferences must have mode `0600`.

The three synthetic vaults cover JSON, pasted fields and legacy per-field storage. Ciphertext file hashes must remain identical across reads and process restart, including the legacy fixture; no automatic migration is allowed. Only these verified fixture vaults are cleaned up. Failed runs retain their UUID and last confirmed phase for diagnosis; explicit failed-fixture cleanup is distinct from an acceptance pass. Evidence stays local and must not contain owner identity. The test does not validate real-device unlock, owner secrets, hardware-backed key attestation or an identity-plus-template transaction.

`velumIdentityPersistenceTest` enables only `src/identityPersistenceTest/kotlin` and its isolated test runner; it defaults to false. The existing instrumented tests remain in the standard source folder. Rebuild Android test APKs without that property before running the normal device-walk runner. Default test APKs and production APKs do not include the synthetic runner or fixtures. Non-secret UUID/PID metadata is returned through instrumentation results, so the R8 target remains nondebuggable; no `run-as` access or public data provider is needed.
