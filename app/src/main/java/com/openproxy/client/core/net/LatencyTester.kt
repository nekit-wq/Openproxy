package com.openproxy.client.core.net

import com.openproxy.client.core.model.ServerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Measures a TCP handshake to the server. This is a reachability hint, not a full
 * proxy URL-test: for UDP-first protocols (WireGuard/Hysteria/TUIC) port 443 is probed
 * because a TCP handshake to a UDP port is meaningless.
 */
class LatencyTester(
    private val timeoutMs: Int = 3000,
    concurrency: Int = 16,
) {
    private val semaphore = Semaphore(concurrency)

    suspend fun measure(profile: ServerProfile): Int = withContext(Dispatchers.IO) {
        semaphore.withPermit {
            val port = if (profile.protocol.udpFirst) 443 else profile.port
            probe(profile.address, port)
        }
    }

    suspend fun measureAll(
        profiles: List<ServerProfile>,
        onResult: (ServerProfile, Int) -> Unit,
    ) = coroutineScope {
        profiles.map { profile ->
            async {
                val latency = measure(profile)
                onResult(profile, latency)
            }
        }.awaitAll()
    }

    private fun probe(address: String, port: Int): Int {
        val started = System.nanoTime()
        return try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(address, port), timeoutMs)
            }
            ((System.nanoTime() - started) / 1_000_000L).toInt().coerceAtLeast(1)
        } catch (e: Exception) {
            -1
        }
    }
}
