package dev.velum.app.platform

/** BCP-47 language tag from the host OS (e.g. `en-US`, `pt-BR`). */
expect fun systemLanguageTag(): String
