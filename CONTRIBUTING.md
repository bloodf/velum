# Contributing to Velum

Thanks for helping build a local-first, subscription-free recorder companion.

## Ground rules

1. **No secrets.** Never commit API keys, Plaud account tokens, device serial signatures, RSA private keys, Fernet keys, or anything from a decompiled APK’s assets. Device identity is imported by the user at runtime.
2. **No Plaud branding.** Do not copy Plaud logos, marketing copy, or third-party “community template” prompt texts. Write original Velum templates.
3. **Interoperability only.** Reverse-engineering notes exist so Velum can talk to hardware the user owns. Prefer read-only BLE commands until download/decrypt is proven safe.
4. **Apache-2.0.** By contributing, you license your work under the Apache License 2.0.

## Dev setup

- JDK 17+
- Android Studio / SDK 35 for Android
- Xcode for iOS (macOS)

```bash
./gradlew :plaud-protocol:jvmTest :ai:jvmTest
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:run
```

## Project layout

| Module | Role |
|--------|------|
| `plaud-protocol` | Pure Kotlin codec + unit tests (no Android/BLE deps) |
| `core` | Domain + settings + secure store interfaces |
| `ai` | OpenAI-compatible HTTP client |
| `ble` | Transport interface; Kable on mobile; stub on desktop |
| `composeApp` | Shared Compose UI + platform entry points |

## Pull requests

- Keep PRs focused; prefer small modules and tests for protocol/byte parsing.
- Add fixtures as hex dumps in tests — never as real encrypted user recordings with private keys.
- Update `docs/protocol/` when you learn something new about framing/opcodes, and credit sources.
- Run JVM tests before pushing.

## Code style

- Kotlin official style; exhaustive `when` on sealed types.
- Imports at the top of the file (no inline imports).
- Prefer interfaces at module boundaries (`BleTransport`, `SecureStore`, `FileDecryptor`, `SessionCipher`).

## Security & privacy

- Treat voice as sensitive personal data (LGPD / similar). Opt-in for any speaker embeddings later.
- Log carefully: never log API keys, PEM material, or bind tokens.
