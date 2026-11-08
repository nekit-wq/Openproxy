package com.openproxy.client.core.parse

sealed interface ImportSource {
    data class Subscription(val url: String) : ImportSource
    data class Links(val text: String) : ImportSource
}

/** Decides whether the pasted text is a subscription URL, a config, or share links. */
object ImportDetector {

    fun detect(raw: String): ImportSource? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (text.startsWith("http://", true) || text.startsWith("https://", true)) {
            return ImportSource.Subscription(text)
        }
        return ImportSource.Links(text)
    }
}
