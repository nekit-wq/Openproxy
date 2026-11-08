package com.openproxy.client.core.parse

import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Base64 helpers that work with every variant providers use: standard, URL-safe,
 * padded, unpadded and wrapped across lines.
 */
object Base64Codec {

    fun decode(value: String): String? = decodeBytes(value)?.toString(Charsets.UTF_8)

    fun decodeBytes(value: String): ByteArray? {
        val cleaned = buildString {
            for (ch in value) {
                if (ch.isWhitespace()) continue
                append(
                    when (ch) {
                        '-' -> '+'
                        '_' -> '/'
                        else -> ch
                    },
                )
            }
        }
        if (cleaned.isEmpty()) return null
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        return try {
            Base64.getDecoder().decode(padded)
        } catch (e: IllegalArgumentException) {
            try {
                Base64.getMimeDecoder().decode(cleaned)
            } catch (e2: IllegalArgumentException) {
                null
            }
        }
    }

    /** True when the whole text looks like a base64 blob of share links or a config. */
    fun looksLikeBase64(value: String): Boolean {
        val text = value.trim()
        if (text.length < 16) return false
        if (text.contains("://")) return false
        if (!text.all { it.isLetterOrDigit() || it in "+/=_- \n\r\t" }) return false
        val decoded = decode(text) ?: return false
        val trimmedDecoded = decoded.trimStart()
        return decoded.contains("://") || decoded.contains("[Interface]") ||
            trimmedDecoded.startsWith("{") || trimmedDecoded.startsWith("[") ||
            decoded.contains("proxies:") || decoded.contains("proxy:")
    }
}

/** Percent-decoding that keeps '+' intact (important for base64 user-info). */
fun percentDecode(value: String): String {
    if (!value.contains('%')) return value
    val out = ByteArrayOutputStream(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '%' && i + 2 < value.length) {
            val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
            if (hex != null) {
                out.write(hex)
                i += 3
                continue
            }
        }
        val bytes = c.toString().toByteArray(Charsets.UTF_8)
        out.write(bytes, 0, bytes.size)
        i++
    }
    return out.toString("UTF-8")
}

internal data class LinkParts(
    val scheme: String,
    val userInfo: String,
    val address: String,
    val port: Int,
    val query: Map<String, String>,
    val fragment: String,
)

internal object LinkUtil {

    fun parse(link: String): LinkParts? {
        val schemeEnd = link.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = link.substring(0, schemeEnd).lowercase()
        var rest = link.substring(schemeEnd + 3)

        var fragment = ""
        val hash = rest.indexOf('#')
        if (hash >= 0) {
            fragment = percentDecode(rest.substring(hash + 1))
            rest = rest.substring(0, hash)
        }

        var queryRaw = ""
        val question = rest.indexOf('?')
        if (question >= 0) {
            queryRaw = rest.substring(question + 1)
            rest = rest.substring(0, question)
        }

        rest = rest.trim().trimEnd('/')
        rest = rest.removePrefix("//")

        var userInfo = ""
        val at = rest.lastIndexOf('@')
        if (at >= 0) {
            userInfo = percentDecode(rest.substring(0, at))
            rest = rest.substring(at + 1)
        }

        var address: String
        var portText = ""
        if (rest.startsWith("[")) {
            val close = rest.indexOf(']')
            if (close < 0) return null
            address = rest.substring(1, close)
            val after = rest.substring(close + 1)
            if (after.startsWith(":")) portText = after.substring(1)
        } else {
            val lastColon = rest.lastIndexOf(':')
            address = if (lastColon > 0 && rest.indexOf(':') == lastColon) {
                portText = rest.substring(lastColon + 1)
                rest.substring(0, lastColon)
            } else {
                rest
            }
        }
        address = address.trim()
        if (address.isEmpty()) return null

        return LinkParts(
            scheme = scheme,
            userInfo = userInfo,
            address = address,
            port = portText.substringBefore('/').toIntOrNull() ?: 0,
            query = parseQuery(queryRaw),
            fragment = fragment.trim(),
        )
    }

    fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val clean = raw.replace("&amp;", "&")
        val map = LinkedHashMap<String, String>()
        for (pair in clean.split('&')) {
            if (pair.isBlank()) continue
            val eq = pair.indexOf('=')
            if (eq < 0) {
                map[percentDecode(pair)] = ""
            } else {
                map[percentDecode(pair.substring(0, eq))] = percentDecode(pair.substring(eq + 1))
            }
        }
        return map
    }
}

internal fun String.truthy(): Boolean {
    val v = trim().lowercase()
    return v == "1" || v == "true" || v == "yes" || v == "on"
}
