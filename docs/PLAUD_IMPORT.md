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
