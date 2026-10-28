# Import from Plaud (one-time identity + optional templates)

Velum never contacts Plaud servers. Device identity (`snSignature`, bind token, optional RSA PEMs) must be imported once from the owner’s logged-in Plaud app (storage/export) or the USB extract route. See `docs/APK_ANALYSIS.md`.

## Identity bundle format (version 1)

Versioned JSON document produced by the USB extract route (issue #3) or an equivalent local Plaud-app export. **Field names only below — never commit, paste, or log real values.**

| Field | Required | Constraints |
|-------|----------|-------------|
| `version` | yes | Integer; currently only `1` is accepted |
| `source` | yes | Non-blank string naming the producer (`usb_route`, `plaud_app_export`, …) |
| `device.serial` | yes | Non-blank device serial string (bounded length) |
| `device.snSignatureBase64` | yes | Base64 of **exactly 256 bytes** (FE10 / `sn-sign`) |
| `device.bindToken` | yes | Non-blank bind token / user id (bounded length) |
| `device.publicKeyPem` | no | PEM text for user E2EE public key (bounded length) |
| `device.privateKeyPem` | no | PEM text for user E2EE private key (bounded length) |
| `templates` | no | Optional custom-template array (import UI); ignored by the identity parser |

Shared parser: `dev.velum.protocol.identity.IdentityBundleParser` in `:plaud-protocol`. It rejects empty, truncated, oversized, wrong-version, and garbage input with typed errors whose messages never include secret field values.

```json
{
  "version": 1,
  "source": "usb_route",
  "device": {
    "serial": "<serial>",
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
- Test fixtures must be **synthetic and marked test-only** — never real device data.
- Future emulator dumps (templates catalog, custom templates, identity) should use this file.

## Security

Identity values (token, PEMs, signatures, serial) are secrets:

- Never print, log, or put them in exception messages, `toString()`, crash reports, test failure output that echoes payloads, or commit text.
- Parser / import failures must use field **names** and structural reasons only (e.g. wrong length), never the rejected value.
- Android Keystore AES-256-GCM persistence (non-exportable key; ciphertext in private prefs) is issue #92; real-owner hardware check remains #4. JVM tests use an AES-GCM software-key stand-in.
- **Vault retention:** drop a single prefs entry only when its ciphertext blob is structurally malformed (bad length/format). Crypto, auth, or Keystore failures keep that entry and return null.
- **Keystore wipe:** regenerate the Keystore key **and** clear every stored entry only on `KeyPermanentlyInvalidatedException` (the old key is gone). A transient Keystore error must never clear the vault. Classification is a pure function (`KeystoreFailureClassifier`) so JVM tests cover it without Robolectric.
- **Keystore alias:** if the alias exists but `getEntry` fails transiently (including the API 35 emulator NPE), keep the alias and every entry — do not mint a replacement key (that would strand all ciphertext). Only create a key when the alias is absent, or after permanent invalidation clears the vault.

## UI

Settings → **Import from Plaud**: paste fields and/or choose a JSON file (Android SAF). Material is stored via `SecureStore` (Android Keystore).
