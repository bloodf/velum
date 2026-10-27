# Velum privacy policy

**Last updated:** 2026-10-27

Velum is a **local-first** open-source companion for personal recorders (including Plaud Note Pro) and phone microphones. There is **no Velum cloud account**, **no telemetry**, and **no crash reporting**.

This document is the privacy policy referenced by Play / F-Droid listings. Hosted copy:
`https://github.com/bloodf/velum/blob/main/docs/PRIVACY.md`

## What stays on your device

- Recordings, transcripts, summaries, templates, AutoFlow rules, voice profiles
- Device identity material (serial signature, bind token, optional RSA keys) in the Android Keystore–backed store
- Optional BYO API key and webhook HMAC secret (Keystore)
- Optional export folder URI (Storage Access Framework)

Velum does **not** upload your audio or notes to a Velum server (there is none).

## Permissions (Android)

| Permission | Why |
|---|---|
| Microphone | Phone recording |
| Bluetooth / nearby devices | Talk to your recorder over BLE |
| Calendar (optional) | Name recordings from the current event; AutoFlow calendar insert |
| Notifications | Foreground service while recording / processing |
| Storage Access Framework | Import audio / export Markdown to a folder you pick |

## Network

- **Optional** HTTPS calls to an OpenAI-compatible API **you** configure (base URL + key)
- **Optional** model catalog downloads over HTTPS (SHA-256 verified)
- **Optional** user-configured webhook POST (Zapier / n8n / Make)
- BLE to your hardware; SoftAP Wi‑Fi transfer stays on the local SoftAP — no Plaud cloud hosts

## Data safety (Play Console summary)

- Collected: none by Velum servers
- Shared: only if you enable a BYO API, webhook, or share/export yourself
- Sensitive: voice may be processed as biometric-like data for optional speaker profiles — stays on device; opt-in flows only

## Children

Velum is not directed at children under 13.

## Contact

Issues: https://github.com/bloodf/velum/issues

Not affiliated with Plaud Inc. See also [LEGAL.md](LEGAL.md).
