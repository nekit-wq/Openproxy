package com.openproxy.client.core.parse

import com.openproxy.client.core.model.Protocol
import com.openproxy.client.core.model.ServerProfile

/**
 * Reads wireguard-quick / AmneziaWG .conf files.
 * Interface and Peer sections are flattened into a flat map, so unknown keys are harmless.
 */
object WireGuardConfigParser {

    fun isWireGuardConfig(text: String): Boolean =
        SECTION_INTERFACE.containsMatchIn(text) && SECTION_PEER.containsMatchIn(text)

    fun parse(text: String, nameOverride: String = ""): ServerProfile? {
        val values = parseIni(text)
        val privateKey = values["interface.privatekey"] ?: return null
        val endpoint = values["peer.endpoint"] ?: return null
        val hostPort = splitEndpoint(endpoint) ?: return null

        val awg = buildMap {
            for (key in listOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4")) {
                values["interface.$key"]?.let { put(key, it) }
            }
            for (index in 1..4) {
                values["interface.i$index"]?.let { put("i$index", it) }
            }
        }

        val address = values["interface.address"]
            ?.split(',')
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotEmpty() }
            ?: "10.0.0.2/32"

        return ServerProfile(
            name = nameOverride.ifBlank { "WireGuard ${hostPort.first}" },
            protocol = if (awg.isNotEmpty()) Protocol.AMNEZIA_WG else Protocol.WIREGUARD,
            address = hostPort.first,
            port = hostPort.second,
            privateKey = privateKey,
            peerPublicKey = values["peer.publickey"].orEmpty(),
            presharedKey = values["peer.presharedkey"].orEmpty(),
            localAddress = address,
            allowedIps = values["peer.allowedips"] ?: "0.0.0.0/0, ::/0",
            dns = values["interface.dns"].orEmpty(),
            mtu = values["interface.mtu"]?.toIntOrNull() ?: 1420,
            keepalive = values["peer.persistentkeepalive"]?.toIntOrNull() ?: 25,
            awg = awg,
            network = "udp",
            rawConfig = text,
        )
    }

    private fun parseIni(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var section = ""
        for (rawLine in text.lines()) {
            val line = rawLine.substringBefore('#').substringBefore(';').trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim().lowercase()
                continue
            }
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim().lowercase()
            val value = line.substring(eq + 1).trim()
            if (value.isNotEmpty()) result["$section.$key"] = value
        }
        return result
    }

    internal fun splitEndpoint(value: String): Pair<String, Int>? {
        val text = value.trim()
        if (text.startsWith("[")) {
            val close = text.indexOf(']')
            if (close < 0) return null
            val host = text.substring(1, close)
            val port = text.substring(close + 1).removePrefix(":").toIntOrNull() ?: return null
            return host to port
        }
        val colon = text.lastIndexOf(':')
        if (colon <= 0) return null
        return text.substring(0, colon) to (text.substring(colon + 1).toIntOrNull() ?: return null)
    }

    private val SECTION_INTERFACE = Regex("(?im)^\\s*\\[interface]")
    private val SECTION_PEER = Regex("(?im)^\\s*\\[peer]")
}
