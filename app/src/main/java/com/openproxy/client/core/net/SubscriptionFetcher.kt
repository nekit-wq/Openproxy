package com.openproxy.client.core.net

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class SubscriptionFetcher(
    private val client: OkHttpClient = defaultClient(),
) {

    data class Response(
        val body: String,
        val headers: Map<String, String>,
        val finalUrl: String,
    )

    suspend fun fetch(
        url: String,
        userAgent: String = DEFAULT_USER_AGENT,
        deviceId: String = "",
    ): Response = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        val normalizedUrl = when {
            trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            else -> "https://$trimmed"
        }

        val primaryUa = when {
            normalizedUrl.contains("clash", ignoreCase = true) -> CLASH_USER_AGENT
            normalizedUrl.contains("sing-box", ignoreCase = true) || normalizedUrl.contains("singbox", ignoreCase = true) -> SINGBOX_USER_AGENT
            else -> userAgent.ifBlank { DEFAULT_USER_AGENT }
        }

        val candidates = linkedSetOf<String>()
        candidates.add(primaryUa)
        candidates.add(DEFAULT_USER_AGENT)
        candidates.add(CLASH_USER_AGENT)
        candidates.add(SINGBOX_USER_AGENT)

        var lastError: Exception? = null
        for ((index, ua) in candidates.withIndex()) {
            val isLast = index == candidates.size - 1
            try {
                val requestBuilder = Request.Builder()
                    .url(normalizedUrl)
                    .header("User-Agent", ua)
                    .header("Accept", "*/*")
                    .header("Accept-Language", "ru,en;q=0.9")

                if (deviceId.isNotBlank()) {
                    // Remnawave and similar panels require HWID device headers
                    requestBuilder.header("x-hwid", deviceId)
                    requestBuilder.header("x-device-os", "Android")
                    requestBuilder.header("x-ver-os", runCatching { Build.VERSION.RELEASE }.getOrNull() ?: "14")
                    requestBuilder.header("x-device-model", runCatching { Build.MODEL }.getOrNull() ?: "Android")
                }

                val response = client.newCall(requestBuilder.build()).execute()
                val code = response.code
                val body = response.body?.string().orEmpty()
                val headers = buildMap {
                    for (name in response.headers.names()) {
                        put(name.lowercase(), response.headers[name].orEmpty())
                    }
                }
                val finalUrl = response.request.url.toString()
                response.close()

                val trimmedBody = body.trim()
                val isRejected = trimmedBody.equals("app not supported", ignoreCase = true) ||
                    trimmedBody.equals("app not suported", ignoreCase = true) ||
                    (trimmedBody.contains("not support", ignoreCase = true) && !trimmedBody.contains("://") && !trimmedBody.contains("proxies:"))

                if (!response.isSuccessful) {
                    if (!isLast && (isRejected || code in 400..404)) {
                        continue
                    }
                    error("Сервер вернул HTTP $code")
                }

                if (isRejected && !isLast) {
                    continue
                }

                return@withContext Response(
                    body = body,
                    headers = headers,
                    finalUrl = finalUrl,
                )
            } catch (e: Exception) {
                lastError = e
                if (isLast) throw e
            }
        }
        throw lastError ?: IllegalStateException("Не удалось загрузить подписку")
    }

    companion object {
        // v2rayNG UA is the universal standard recognized by Remnawave, Marzban, X-UI, V2Board, 3X-UI
        const val DEFAULT_USER_AGENT = "v2rayNG/1.8.12 (Linux; Android)"
        const val CLASH_USER_AGENT = "ClashMeta/1.18.1"
        const val SINGBOX_USER_AGENT = "sing-box/1.10.0 (Android)"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}
