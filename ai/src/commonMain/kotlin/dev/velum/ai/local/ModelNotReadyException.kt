package dev.velum.ai.local

/** Saved with a failed recording, and the UI swaps it for translated copy. No model id or status: users see it. */
const val LOCAL_MODEL_NOT_READY_MESSAGE =
    "A model this step needs isn't downloaded yet. Download it in Model manager, or switch this step to API in AI processing."

/** Thrown when a local engine is asked to run without a Ready on-disk model. */
class ModelNotReadyException(val modelId: String) : IllegalStateException(LOCAL_MODEL_NOT_READY_MESSAGE)
