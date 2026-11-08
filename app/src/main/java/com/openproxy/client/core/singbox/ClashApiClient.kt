package com.openproxy.client.core.singbox

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Talks to the loopback Clash API that the generated config exposes.
 * `/traffic` is a streaming endpoint: one JSON object per line with cumulative bytes.
 */
class ClashApiClient(
    private val port: Int,
    private val secret: String,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun trafficFlow(): Flow<Pair<Long, Long>> = flow {
        val request = Request.Builder()
            .url("http://127.0.0.1:$port/traffic")
            .header("Authorization", "Bearer $secret")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Clash API HTTP ${response.code}")
            val source = response.body?.source() ?: return@use
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                val up = obj["up"]?.jsonPrimitive?.longOrNull ?: 0L
                val down = obj["down"]?.jsonPrimitive?.longOrNull ?: 0L
                emit(up to down)
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun delay(proxyTag: String = SingBoxConfigBuilder.PROXY_TAG, timeoutMs: Int = 5000): Int =
        withContext(Dispatchers.IO) {
            val encodedTag = URLEncoder.encode(proxyTag, "UTF-8")
            val url = "http://127.0.0.1:$port/proxies/$encodedTag/delay" +
                "?url=${URLEncoder.encode("https://www.gstatic.com/generate_204", "UTF-8")}" +
                "&timeout=$timeoutMs"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $secret")
                .build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext -1
                    val body = response.body?.string().orEmpty()
                    val obj = json.parseToJsonElement(body).jsonObject
                    obj["delay"]?.jsonPrimitive?.longOrNull?.toInt() ?: -1
                }
            }.getOrDefault(-1)
        }
}
