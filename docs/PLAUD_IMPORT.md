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
