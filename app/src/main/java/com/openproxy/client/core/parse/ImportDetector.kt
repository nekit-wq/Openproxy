package com.openproxy.client.core.parse

sealed interface ImportSource {
    data class Subscription(val url: String) : ImportSource
    data class Links(val text: String) : ImportSource
}

/** Decides whether the pasted text is a subscription URL, a deep link, a config, or share links. */
object ImportDetector {

    fun detect(raw: String): ImportSource? {
        val text = raw.trim().trim('"', '\'', '`', '<', '>')
        if (text.isEmpty()) return null

        // 1. Deep link schemes from Telegram bots / web dashboards
        extractDeepLinkSubscription(text)?.let {
            return ImportSource.Subscription(it)
        }

        // 2. Direct HTTP / HTTPS link
        if (text.startsWith("http://", true) || text.startsWith("https://", true)) {
            // If it's an HTTP proxy share link like http://user:pass@host:port#name
            if (isHttpProxyShareLink(text)) {
                return ImportSource.Links(text)
            }
            return ImportSource.Subscription(text)
        }

        // 3. Domain URL without scheme: e.g. sub.example.com/... or starts with sub.
        if (isDomainSubscriptionUrl(text)) {
            return ImportSource.Subscription("https://$text")
        }

        // 4. Text containing an http(s) URL inside message (e.g. "Ваша подписка: https://sub...")
        extractEmbeddedHttpUrl(text)?.let {
            return ImportSource.Subscription(it)
        }

        return ImportSource.Links(text)
    }

    private fun extractDeepLinkSubscription(text: String): String? {
        val trimmed = text.trim()

        // clash://install-config?url=...
        // v2rayng://install-config?url=... or v2rayng://install-sub?url=...
        // sing-box://import-remote-profile?url=...
        // sn://subscription?url=...
        // happ://...
        val schemes = listOf("clash://", "v2rayng://", "sing-box://", "sn://", "happ://", "streisand://")
        for (scheme in schemes) {
            if (trimmed.startsWith(scheme, ignoreCase = true)) {
                val query = trimmed.substringAfter('?')
                val params = LinkUtil.parseQuery(query)
                val targetUrl = params["url"] ?: params["link"] ?: params["sub"]
                if (!targetUrl.isNullOrBlank()) {
                    return targetUrl
                }
            }
        }

        // hiddify://import/https://...
        if (trimmed.startsWith("hiddify://import/", ignoreCase = true)) {
            val target = trimmed.substringAfter("hiddify://import/").trim()
            if (target.startsWith("http://", true) || target.startsWith("https://", true)) {
                return target
            }
        }

        // sub://<base64>
        if (trimmed.startsWith("sub://", ignoreCase = true)) {
            val encoded = trimmed.substringAfter("sub://").substringBefore('#').trim()
            val decoded = Base64Codec.decode(encoded)?.trim().orEmpty()
            if (decoded.startsWith("http://", true) || decoded.startsWith("https://", true)) {
                return decoded
            }
        }

        return null
    }

    private fun isHttpProxyShareLink(text: String): Boolean {
        // http(s)://user:pass@host:port#name
        val withoutScheme = text.substringAfter("://").substringBefore('?').substringBefore('#')
        return withoutScheme.contains('@') && withoutScheme.contains(':')
    }

    private fun isDomainSubscriptionUrl(text: String): Boolean {
        if (text.contains("://")) return false
        val firstToken = text.lines().firstOrNull()?.trim().orEmpty()
        if (firstToken.contains(' ') || !firstToken.contains('/')) return false
        val host = firstToken.substringBefore('/')
        return host.startsWith("sub.", ignoreCase = true) ||
            host.contains(".sub.", ignoreCase = true) ||
            (host.contains('.') && !host.contains('@') && !host.startsWith("["))
    }

    private fun extractEmbeddedHttpUrl(text: String): String? {
        val lines = text.lines()
        for (line in lines) {
            val match = Regex("https?://[^\\s\"'<>`]+").find(line)
            if (match != null) {
                val url = match.value
                if (!isHttpProxyShareLink(url)) {
                    return url
                }
            }
        }
        return null
    }
}
