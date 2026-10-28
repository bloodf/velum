# Android 1.0 hardware validation checklist

Run on the owner’s **Plaud Note Pro** + a mid-range Android phone (arm64).  
Do **not** send opcodes **5, 104, 128, 0xFE20, 143 (OTA)** or write settings **4 / 26**.

Use a green CI **`velum-debug-apk`** artifact (fixed debug keystore) or a signed release build.

## Preconditions

- [ ] Identity JSON imported once (Settings → device keys / Plaud JSON v1). Values never leave the Keystore-backed store.
- [ ] App language pt-BR; dark theme optional for UI checks.
- [ ] Recommended local models downloaded (or API configured) for ASR / diarization / summary.

## Device identity (#3 / #4)

- [ ] USB extract → JSON validates against `docs/PLAUD_IMPORT.md` (owner machine only).
- [ ] Import from JSON and from paste fields; survives process death; malformed JSON shows a clear error.
- [ ] Logs never print `snSignature` / bind token / PEMs.

## BLE (#5)

- [ ] Scan finds Note Pro; advertising shows expected project / `portVersion` (881 family).
- [ ] Unlock with imported keys; session survives reconnect.
- [ ] Battery level shows and updates.
- [ ] Confirm forbidden opcodes are never observed on the wire.

## Multi-hour download (#6)

- [ ] Record ≥2 h on device; BLE download completes; CRC matches.
- [ ] Interrupt mid-transfer (BT off / kill app / range); resume from last offset.
- [ ] Screen-off + `connectedDevice` session-download FGS (`SessionDownloadService`) keeps transfer alive (background + Doze on S26 Ultra).
- [ ] Process death (swipe away) vs Android force-stop: interrupted download resumes from last confirmed offset without duplicate/missing chunks; force-stop does not auto-resume.
- [ ] Completed / resumed / Wi‑Fi→BLE fallback downloads pass CRC validation.
- [ ] Note device config, reproduction steps, transfer time, and battery drain in `docs/STATUS.md`.

## Audio decrypt / decode (#7)

- [ ] Real capture decrypts (PLAUD.AI) → Ogg Opus → 16 kHz PCM.
- [ ] Playback sounds correct; ASR output sensible.
- [ ] Sample rate / channels documented; add a non-sensitive header fixture if possible.

## SoftAP Wi‑Fi (#8)

- [ ] SoftAP join + per-socket bind; long file transfer CRC OK; throughput noted.
- [ ] Mid-transfer Wi‑Fi drop → BLE fallback.
- [ ] Phone returns to normal network afterward.

## FE10-only (#9)

- [ ] With E2EE off: FE10-only list + download result documented (works / needs FE11).

## Long processing (#10 follow-up)

- [ ] ≥6 h recording processes on a **4 GB RAM** phone without OOM (PCM spill path).
- [ ] Peak RSS noted in `docs/STATUS.md`.

## Local AI (epic #40 — physical)

- [ ] Parakeet / Whisper / Gemma RTF, RAM, thermals on mid-range + flagship (`docs/`).
- [ ] Whistle on low-RAM arm64; pt routes to Whisper Tiny (`docs/WHISTLE_EVAL.md`).
- [ ] Diarization + voice profiles on ≥3 real multi-speaker meetings (pt + en).

## Sign-off

| Item | Build / commit | Tester | Date | Pass? |
|------|----------------|--------|------|-------|
| | | | | |
