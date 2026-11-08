package com.openproxy.client.core.parse

/** Quota / expiry information carried in subscription HTTP headers. */
data class SubscriptionMeta(
    val name: String = "",
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val expiresAtSeconds: Long = 0L,
    val updateIntervalHours: Int = 12,
    val webPageUrl: String = "",
    val supportUrl: String = "",
) {
    val hasQuota: Boolean get() = totalBytes > 0L
    val hasExpiry: Boolean get() = expiresAtSeconds > 0L
}

/**
 * Understands what providers put inside subscription responses:
 * base64 link lists, plain link lists, sing-box JSON — and the `subscription-userinfo`
 * header that carries used/left traffic and the expiry date.
 */
object SubscriptionParser {

    fun parseBody(body: String): ParseBatch {
        val trimmed = body.trim().removePrefix("\uFEFF")
        if (trimmed.isEmpty()) return ParseBatch(errors = listOf("Пустой ответ подписки"))
        if (trimmed.startsWith("<")) {
            return ParseBatch(
                errors = listOf(
                    "Сервер вернул HTML вместо подписки. Проверьте ссылку: возможно, она открывается в браузере.",
                ),
            )
        }
        if (trimmed.equals("app not supported", ignoreCase = true) ||
            trimmed.equals("app not suported", ignoreCase = true) ||
            (trimmed.contains("not support", ignoreCase = true) && !trimmed.contains("://"))
        ) {
            return ParseBatch(
                errors = listOf(
                    "Сервер подписки отклонил запрос (App not supported). Панель требует поддерживаемый клиент или превышен лимит устройств HWID.",
                ),
            )
        }
        if (CLASH_PROXIES.containsMatchIn(trimmed)) {
            return ParseBatch(
                errors = listOf(
                    "Подписка в формате Clash YAML пока не поддерживается. Используйте ссылку на base64-подписку.",
                ),
            )
        }
        val batch = ShareLinkParser.parseMany(trimmed)
        // Filter out dummy error nodes returned by panels when unauthorized (e.g. vless://...@127.0.0.1#App not supported)
        val validProfiles = batch.profiles.filterNot { profile ->
            val isDummyAddress = profile.address == "127.0.0.1" || profile.address == "0.0.0.0" || profile.address.contains("dummy")
            val isUnsupportedName = profile.name.contains("not support", ignoreCase = true) ||
                profile.name.contains("не поддерживается", ignoreCase = true) ||
                profile.name.contains("unsupported", ignoreCase = true)
            isDummyAddress && isUnsupportedName
        }
        val hadDummy = validProfiles.size < batch.profiles.size
        val extraErrors = if (hadDummy && validProfiles.isEmpty()) {
            listOf("Сервер подписки вернул заглушку 'App not supported'. Панель отклонила запрос или исчерпан лимит устройств.")
        } else {
            emptyList()
        }
        return ParseBatch(validProfiles, batch.errors + extraErrors)
    }

    fun parseMeta(headers: Map<String, String>): SubscriptionMeta {
        val normalized = headers.entries.associate { it.key.lowercase() to it.value }
        val userInfo = normalized["subscription-userinfo"].orEmpty()
        var upload = 0L
        var download = 0L
        var total = 0L
        var expire = 0L
        if (userInfo.isNotBlank()) {
            for (part in userInfo.split(';')) {
                val key = part.substringBefore('=', "").trim().lowercase()
                val value = part.substringAfter('=', "").trim().toLongOrNull() ?: continue
                when (key) {
                    "upload" -> upload = value
                    "download" -> download = value
                    "total" -> total = value
                    "expire" -> expire = value
                }
            }
        }
        val rawTitle = normalized["profile-title"].orEmpty()
        val title = when {
            rawTitle.startsWith("base64:", ignoreCase = true) ->
                Base64Codec.decode(rawTitle.substringAfter(':')) ?: ""
            else -> rawTitle
        }
        return SubscriptionMeta(
            name = title.trim(),
            uploadBytes = upload,
            downloadBytes = download,
            totalBytes = total,
            expiresAtSeconds = expire,
            updateIntervalHours = normalized["profile-update-interval"]?.toIntOrNull() ?: 12,
            webPageUrl = normalized["profile-web-page-url"].orEmpty(),
            supportUrl = normalized["support-url"].orEmpty(),
        )
    }

    private val CLASH_PROXIES = Regex("(?im)^\\s*proxies\\s*:")
}
