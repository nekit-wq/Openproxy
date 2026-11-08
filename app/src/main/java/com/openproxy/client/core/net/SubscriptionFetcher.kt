package com.openproxy.client.core.net

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

    suspend fun fetch(url: String): Response = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .build()
        client.newCall(request).execute().use { response ->
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
        // Some panels serve different payloads depending on the client UA.
        private const val USER_AGENT = "OpenProxy/1.0 (Android) sing-box"

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
