package dev.velum.core.templates

internal actual object VelumContributedAsset {
    actual fun readJsonl(): String {
        val stream = VelumContributedAsset::class.java.classLoader
            ?.getResourceAsStream(RESOURCE_NAME)
            ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(RESOURCE_NAME)
            ?: VelumContributedAsset::class.java.getResourceAsStream("/$RESOURCE_NAME")
            ?: return ""
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private const val RESOURCE_NAME = "velum_contributed.jsonl"
}
