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

Malformed or partial bundles are rejected before writing identity or templates. Secure-storage failures remain explicit and retryable; cancellation propagates. A successful multi-field import is not an atomic transaction across identity entries and templates: a later storage failure can follow an earlier successful write. Real-device unlock and persistence checks remain owner-dependent (#3 / #4).

## Synthetic Android persistence verification

Build the isolated test APKs, then choose an explicit emulator and a new empty evidence directory:

```sh
./gradlew :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest -PvelumIdentityPersistenceTest=true --max-workers=2
./gradlew :composeApp:assembleMinifiedTest :composeApp:assembleMinifiedTestAndroidTest -PvelumIdentityPersistenceTest=true -PvelumTestBuildType=minifiedTest --max-workers=2
python3 scripts/check-identity-persistence.py --serial emulator-5554 --output /tmp/velum-identity-check-001
```

Use the JDK/SDK setup in [ENGINEERING_STANDARDS.md](ENGINEERING_STANDARDS.md). The script rejects physical targets and dirty verification trees. Its test-only runner creates a plain `Application`, avoiding `VelumApplication`/`AppContainer` startup. Both JSON and pasted-field APIs use fresh UUID-namespaced preferences and Android Keystore aliases; production vault names are never used. The prepare and verify phases run in different PIDs with different process start times. Verification checks reloaded identity, encrypted preference contents, app UID/GID ownership, private-directory isolation, malformed-input preservation and process-scoped log redaction. Android `MODE_PRIVATE` may use `0660` for the app's own UID/GID; it must not grant other-user access.

The permission assertion follows Android's [ContextImpl implementation](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-14.0.0_r1/core/java/android/app/ContextImpl.java#L3279), rather than assuming that private preferences must have mode `0600`.

Only the two verified synthetic vaults are cleaned up. Failed runs retain their UUID and last confirmed phase for diagnosis; explicit failed-fixture cleanup is distinct from an acceptance pass. Evidence stays local and must not contain owner identity. The test does not validate real-device unlock, owner secrets, hardware-backed key attestation or atomic multi-entry imports.

`velumIdentityPersistenceTest` enables only `src/identityPersistenceTest/kotlin` and its isolated test runner; it defaults to false. The existing instrumented tests remain in the standard source folder. Rebuild Android test APKs without that property before running the normal device-walk runner. Default test APKs and production APKs do not include the synthetic runner or fixtures. Non-secret UUID/PID metadata is returned through instrumentation results, so the R8 target remains nondebuggable; no `run-as` access or public data provider is needed.
