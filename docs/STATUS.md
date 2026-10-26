# Velum status

Checklist for AI / UI pipeline work. Update when a box moves.

## Working

- [x] Compose Markdown preview (`MarkdownPreview`) for Sources / Notes
- [x] Seed transcripts / summaries as Markdown (speakers bold, headings, lists)
- [x] `LocalModelManager` real Ktor downloads to `models/` with disk Ready check
- [x] Catalog ASR URLs for Parakeet / Whisper turbo / SenseVoice (k2-fsa releases)
- [x] BlockedOnUrl for catalog entries without a known download URL (no fake Ready)
- [x] Model delete removes on-disk files
- [x] `RecordingProcessor` pipeline + repository update methods
- [x] Generate Apply / re-transcribe launch processor
- [x] OpenAI model list refresh + clear missing-key errors
- [x] Local engines throw `ModelNotReadyException` (no placeholder success text)
- [x] Ogg Opus demux (pure Kotlin) + kopus decode on Android/JVM
- [x] EN + pt-BR strings for new Model manager states

## In progress

- [ ] Android sherpa-onnx OfflineRecognizer config mapping after archive extract
- [ ] Local diarization (`OfflineSpeakerDiarization`) end-to-end
- [ ] Local LLM (LiteRT-LM / llama.cpp) runtime
- [ ] Archive extract step after model download (`.tar.bz2` → model dir)

## Blocked on owner

- [ ] Download URLs (+ optional SHA-256) for pyannote / WeSpeaker / TitaNet / Gemma / Qwen catalog entries
- [ ] Confirm Plaud Ogg Opus sample-rate / channel assumptions against a real Note Pro capture
- [ ] Product call: default Local vs API once first-run UX for model downloads is designed
