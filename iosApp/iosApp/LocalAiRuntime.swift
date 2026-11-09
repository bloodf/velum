import ComposeApp
import Foundation
import LiteRTLM
import SherpaOnnx
import SherpaOnnxC

private func modelError(_ message: String) -> NSError {
    NSError(domain: "VelumLocalAI", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
}

private final class LocalAiTask: NSObject, IosLocalInferenceTask {
    private let lock = NSLock()
    private var cancelled = false
    private var cancelNative: (() -> Task<Void, Error>)?
    private var cancellationTask: Task<Void, Error>?

    func cancel() {
        lock.lock()
        cancelled = true
        if cancellationTask == nil { cancellationTask = cancelNative?() }
        lock.unlock()
    }

    func installCancellation(_ action: @escaping () -> Task<Void, Error>) {
        lock.lock()
        cancelNative = action
        if cancelled && cancellationTask == nil { cancellationTask = action() }
        lock.unlock()
    }

    private func detachCancellation() -> Task<Void, Error>? {
        lock.lock()
        cancelNative = nil
        let task = cancellationTask
        lock.unlock()
        return task
    }

    func finishCancellation() async {
        if let task = detachCancellation() { _ = await task.result }
    }

    func checkCancellation() throws {
        lock.lock()
        let stopped = cancelled
        lock.unlock()
        if stopped { throw CancellationError() }
    }
}

final class LocalAiRuntime: NSObject, IosLocalInferenceBridge {
    func start(
        request: String, samples: KotlinFloatArray,
        onText: @escaping (String) -> Void,
        onDone: @escaping () -> Void,
        onError: @escaping (String) -> Void
    ) -> IosLocalInferenceTask {
        let owner = LocalAiTask()
        Task.detached(priority: .userInitiated) {
            do {
                guard let data = request.data(using: .utf8),
                      let fields = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let operation = fields["operation"] as? String,
                      let path = fields["modelPath"] as? String,
                      let model = fields["modelId"] as? String else {
                    throw modelError("Invalid local inference request")
                }
                try owner.checkCancellation()
                if operation == "complete" {
                    try await self.complete(fields, path: path, owner: owner, onText: onText)
                } else {
                    let pcm = (0..<samples.size).map { samples.get(index: $0) }
                    let rate = (fields["sampleRate"] as? NSNumber)?.intValue ?? 0
                    guard rate > 0, !pcm.isEmpty else { throw modelError("Expected mono PCM audio") }
                    let result: [String: Any]
                    switch operation {
                    case "transcribe":
                        result = try self.transcribe(pcm, rate: rate, model: model, path: path, fields: fields)
                    case "diarize":
                        result = try self.diarize(pcm, rate: rate, path: path, fields: fields, owner: owner)
                    default:
                        throw modelError("Unknown local inference operation")
                    }
                    try owner.checkCancellation()
                    let json = try JSONSerialization.data(withJSONObject: result)
                    onText(String(decoding: json, as: UTF8.self))
                }
                try owner.checkCancellation()
                await owner.finishCancellation()
                onDone()
            } catch {
                await owner.finishCancellation()
                onError(error.localizedDescription)
            }
        }
        return owner
    }

    private func files(_ path: String) throws -> [URL] {
        guard let enumerator = FileManager.default.enumerator(
            at: URL(fileURLWithPath: path), includingPropertiesForKeys: [.isRegularFileKey],
            options: [.skipsHiddenFiles]
        ) else { throw modelError("Model directory missing") }
        return try enumerator.compactMap { item -> URL? in
            guard let url = item as? URL else { return nil }
            return try url.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile == true ? url : nil
        }.sorted { $0.path < $1.path }
    }

    private func find(_ files: [URL], _ predicate: (String) -> Bool) throws -> String {
        guard let file = files.first(where: { predicate($0.lastPathComponent.lowercased()) }) else {
            throw modelError("Required model weights missing")
        }
        return file.path
    }

    private func complete(
        _ fields: [String: Any], path: String, owner: LocalAiTask,
        onText: @escaping (String) -> Void
    ) async throws {
        let weights = try find(files(path)) { $0.hasSuffix(".litertlm") || $0.hasSuffix(".task") }
        let config = try EngineConfig(modelPath: weights, backend: .cpu(threadCount: 2))
        let engine = Engine(engineConfig: config)
        try await engine.initialize()
        try owner.checkCancellation()
        let conversation = try await engine.createConversation(with: ConversationConfig(
            systemMessage: Message(fields["systemPrompt"] as? String ?? "", role: .system)
        ))
        try owner.checkCancellation()
        // Keep consuming until the native terminal callback, even after cancellation, before releasing handles.
        let stream = await conversation.sendMessageStream(
            Message(fields["userPrompt"] as? String ?? ""),
            maxOutputTokens: (fields["maxOutputTokens"] as? NSNumber)?.intValue
        )
        // Stream construction submits native work before a pending cancellation is dispatched.
        owner.installCancellation { Task { try await conversation.cancel() } }
        for try await message in stream {
            for content in message.contents {
                if case .text(let text) = content { onText(text) }
            }
        }
        try owner.checkCancellation()
    }

    private func transcribe(
        _ samples: [Float], rate: Int, model: String, path: String, fields: [String: Any]
    ) throws -> [String: Any] {
        let weights = try files(path)
        let tokens = try find(weights) { $0.contains("tokens") && $0.hasSuffix(".txt") }
        let language = (fields["language"] as? String ?? "").split(separator: "-").first.map(String.init) ?? ""
        var modelConfig = sherpaOnnxOfflineModelConfig(tokens: tokens, numThreads: 2)
        if model.contains("whisper") {
            modelConfig.whisper = sherpaOnnxOfflineWhisperModelConfig(
                encoder: try find(weights) { $0.contains("encoder") && $0.hasSuffix(".onnx") },
                decoder: try find(weights) { $0.contains("decoder") && $0.hasSuffix(".onnx") },
                language: language == "auto" ? "" : language
            )
            modelConfig.model_type = toCPointer("whisper")
        } else if model.contains("sensevoice") {
            modelConfig.sense_voice = sherpaOnnxOfflineSenseVoiceModelConfig(
                model: try find(weights) { $0.hasPrefix("model") && $0.hasSuffix(".onnx") },
                language: language.isEmpty ? "auto" : language, useInverseTextNormalization: true
            )
        } else {
            modelConfig.transducer = sherpaOnnxOfflineTransducerModelConfig(
                encoder: try find(weights) { $0.contains("encoder") && $0.hasSuffix(".onnx") },
                decoder: try find(weights) { $0.contains("decoder") && $0.hasSuffix(".onnx") },
                joiner: try find(weights) { $0.contains("joiner") && $0.hasSuffix(".onnx") }
            )
            modelConfig.model_type = toCPointer("nemo_transducer")
        }
        var config = sherpaOnnxOfflineRecognizerConfig(
            featConfig: sherpaOnnxFeatureConfig(sampleRate: 16000, featureDim: 80), modelConfig: modelConfig
        )
        guard let recognizer = SherpaOnnxCreateOfflineRecognizer(&config) else { throw modelError("Cannot load Sherpa model") }
        defer { SherpaOnnxDestroyOfflineRecognizer(recognizer) }
        guard let stream = SherpaOnnxCreateOfflineStream(recognizer) else { throw modelError("Cannot create ASR stream") }
        defer { SherpaOnnxDestroyOfflineStream(stream) }
        SherpaOnnxAcceptWaveformOffline(stream, Int32(rate), samples, Int32(samples.count))
        // ponytail: Sherpa has no decode abort API; cancellation discards the result after this input window.
        SherpaOnnxDecodeOfflineStream(recognizer, stream)
        guard let result = SherpaOnnxGetOfflineStreamResult(stream) else { throw modelError("Sherpa inference failed") }
        defer { SherpaOnnxDestroyOfflineRecognizerResult(result) }
        guard let text = result.pointee.text else { throw modelError("Sherpa returned no text") }
        return ["text": String(cString: text), "language": result.pointee.lang.map { String(cString: $0) } ?? language]
    }

    private func diarize(
        _ samples: [Float], rate: Int, path: String, fields: [String: Any], owner: LocalAiTask
    ) throws -> [String: Any] {
        guard let embeddingPath = fields["embeddingPath"] as? String else { throw modelError("Embedding path missing") }
        let embedding = try find(files(embeddingPath)) { $0.hasSuffix(".onnx") }
        var config = sherpaOnnxOfflineSpeakerDiarizationConfig(
            segmentation: sherpaOnnxOfflineSpeakerSegmentationModelConfig(
                pyannote: sherpaOnnxOfflineSpeakerSegmentationPyannoteModelConfig(
                    model: try find(files(path)) { $0.hasSuffix(".onnx") }
                ), numThreads: 2
            ),
            embedding: sherpaOnnxSpeakerEmbeddingExtractorConfig(model: embedding, numThreads: 2),
            clustering: sherpaOnnxFastClusteringConfig()
        )
        let diarizer = SherpaOnnxOfflineSpeakerDiarizationWrapper(config: &config)
        guard diarizer.impl != nil, diarizer.sampleRate == rate else { throw modelError("Diarizer model or sample rate invalid") }
        let segments = diarizer.process(samples: samples)
        try owner.checkCancellation()
        var embeddingConfig = sherpaOnnxSpeakerEmbeddingExtractorConfig(model: embedding, numThreads: 2)
        let extractor = SherpaOnnxSpeakerEmbeddingExtractorWrapper(config: &embeddingConfig)
        guard extractor.impl != nil else { throw modelError("Cannot load embedding model") }
        var embeddings: [String: [Float]] = [:]
        for (speaker, spans) in Dictionary(grouping: segments, by: { $0.speaker }) {
            try owner.checkCancellation()
            guard let span = spans.max(by: { $0.end - $0.start < $1.end - $1.start }) else { continue }
            let start = max(0, min(samples.count, Int(span.start * Float(rate))))
            let end = max(start, min(samples.count, Int(span.end * Float(rate))))
            if end == start { continue }
            let stream = extractor.createStream()
            stream.acceptWaveform(samples: Array(samples[start..<end]), sampleRate: rate)
            stream.inputFinished()
            if extractor.isReady(stream: stream) { embeddings["S\(speaker + 1)"] = extractor.compute(stream: stream) }
        }
        let spans: [[String: Any]] = segments.map {
            ["speaker": "S\($0.speaker + 1)", "startMs": Int64(max(0, $0.start) * 1000), "endMs": Int64(max(0, $0.end) * 1000)]
        }
        return ["spans": spans, "embeddings": embeddings]
    }
}
