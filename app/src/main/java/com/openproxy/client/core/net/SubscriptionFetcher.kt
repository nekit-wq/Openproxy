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

        val requestBuilder = Request.Builder()
            .url(normalizedUrl)
            .header("User-Agent", userAgent.ifBlank { DEFAULT_USER_AGENT })
            .header("Accept", "*/*")
            .header("Accept-Language", "ru,en;q=0.9")

        if (deviceId.isNotBlank()) {
            // Remnawave and similar panels require HWID device headers
            requestBuilder.header("x-hwid", deviceId)
            requestBuilder.header("x-device-os", "Android")
            requestBuilder.header("x-ver-os", runCatching { Build.VERSION.RELEASE }.getOrNull() ?: "14")
            requestBuilder.header("x-device-model", runCatching { Build.MODEL }.getOrNull() ?: "Android")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                error("Сервер вернул HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            val headers = buildMap {
                for (name in response.headers.names()) {
                    put(name.lowercase(), response.headers[name].orEmpty())
                }
            }
            Response(
                body = body,
                headers = headers,
                finalUrl = response.request.url.toString(),
            )
        }
    }

    companion object {
        // v2rayNG UA is the universal standard recognized by Remnawave, Marzban, X-UI, V2Board, 3X-UI
        const val DEFAULT_USER_AGENT = "v2rayNG/1.8.12 (Linux; Android)"

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
