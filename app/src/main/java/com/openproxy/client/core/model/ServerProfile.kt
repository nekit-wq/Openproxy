package com.openproxy.client.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Flat, serialisable description of one server. It is deliberately protocol-agnostic:
 * unused fields are simply empty. Profiles are stored as JSON, so adding a field later
 * never breaks existing imports.
 */
@Serializable
data class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val protocol: Protocol,
    val address: String,
    val port: Int,
    /** VLESS/VMess/TUIC UUID or SSH user. */
    val uuid: String = "",
    val password: String = "",
    val username: String = "",
    /** Shadowsocks cipher or VMess encryption method. */
    val method: String = "",
    /** XTLS flow, e.g. xtls-rprx-vision. */
    val flow: String = "",
    /** Transport security: none | tls | reality. */
    val security: String = "none",
    val sni: String = "",
    val alpn: List<String> = emptyList(),
    val fingerprint: String = "",
    val publicKey: String = "",
    val shortId: String = "",
    val spiderX: String = "",
    /** tcp | ws | grpc | http | httpupgrade | quic | xhttp. */
    val network: String = "tcp",
    val headerType: String = "none",
    val path: String = "",
    val host: String = "",
    val serviceName: String = "",
    val allowInsecure: Boolean = false,
    val plugin: String = "",
    val pluginOptions: String = "",
    val obfs: String = "",
    val obfsPassword: String = "",
    // WireGuard / AmneziaWG
    val privateKey: String = "",
    val peerPublicKey: String = "",
    val presharedKey: String = "",
    val localAddress: String = "10.0.0.2/32",
    val allowedIps: String = "0.0.0.0/0, ::/0",
    val dns: String = "",
    val mtu: Int = 1420,
    val keepalive: Int = 25,
    val reserved: List<Int> = emptyList(),
    val awg: Map<String, String> = emptyMap(),
    /** Original .ovpn / sing-box fragment, kept for export and inspection. */
    val rawConfig: String = "",
    val subscriptionId: String? = null,
    val rawLink: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val latencyMs: Int = -1,
    val latencyCheckedAt: Long = 0L,
    val enabled: Boolean = true,
    val comment: String = "",
) {
    val title: String get() = name.ifBlank { "$address:$port" }

    val hostPort: String
        get() = if (address.contains(':')) "[$address]:$port" else "$address:$port"

    val latencyLabel: String
        get() = when {
            latencyMs < 0 -> "—"
            latencyMs == 0 -> "<1 мс"
            else -> "$latencyMs мс"
        }
}
