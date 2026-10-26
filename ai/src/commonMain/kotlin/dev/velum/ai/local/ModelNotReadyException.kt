package dev.velum.ai.local

/** Thrown when a local engine is asked to run without a Ready on-disk model. */
class ModelNotReadyException(
    val modelId: String,
    message: String = "Local model '$modelId' is not Ready. Download it in Model manager first.",
) : IllegalStateException(message)
