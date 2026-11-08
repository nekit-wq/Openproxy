package com.openproxy.client.core.parse

import com.openproxy.client.core.model.Protocol
import com.openproxy.client.core.model.ServerProfile

/**
 * Minimal .ovpn reader. The original file is preserved in [ServerProfile.rawConfig],
 * so nothing is lost even though the bundled core does not start OpenVPN yet.
 */
object OpenVpnConfigParser {

    fun isOpenVpnConfig(text: String): Boolean =
        CLIENT_DIRECTIVE.containsMatchIn(text) && REMOTE_DIRECTIVE.containsMatchIn(text)

    fun parse(text: String, nameOverride: String = ""): ServerProfile? {
        val remote = REMOTE_DIRECTIVE.find(text) ?: return null
        val host = remote.groupValues[1]
        val proto = PROTO_DIRECTIVE.find(text)?.groupValues?.get(1)?.lowercase() ?: "udp"
        val explicitPort = remote.groupValues[2].toIntOrNull()
        val port = explicitPort ?: if (proto.startsWith("tcp")) 443 else 1194
        val cipher = CIPHER_DIRECTIVE.find(text)?.groupValues?.get(1).orEmpty()
        val authUserPass = AUTH_DIRECTIVE.containsMatchIn(text)
        return ServerProfile(
            name = nameOverride.ifBlank { "OpenVPN $host" },
            protocol = Protocol.OPENVPN,
            address = host,
            port = port,
            method = cipher,
            network = proto,
            security = if (proto.startsWith("tcp")) "tcp" else "udp",
            username = if (authUserPass) "auth-user-pass" else "",
            rawConfig = text,
        )
    }

    private val CLIENT_DIRECTIVE = Regex("(?im)^\\s*client\\s*$")
    private val REMOTE_DIRECTIVE = Regex("(?im)^\\s*remote\\s+(\\S+)(?:\\s+(\\d+))?")
    private val PROTO_DIRECTIVE = Regex("(?im)^\\s*proto\\s+(\\S+)")
    private val CIPHER_DIRECTIVE = Regex("(?im)^\\s*cipher\\s+(\\S+)")
    private val AUTH_DIRECTIVE = Regex("(?im)^\\s*auth-user-pass\\b")
}
