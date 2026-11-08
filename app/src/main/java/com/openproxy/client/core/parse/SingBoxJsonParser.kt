package com.openproxy.client.core.parse

import com.openproxy.client.core.model.Protocol
import com.openproxy.client.core.model.ServerProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Accepts sing-box JSON subscriptions (outbounds / endpoints), which many panels expose
 * alongside the usual base64 link list.
 */
object SingBoxJsonParser {

    fun parse(text: String): ParseBatch? {
        val element = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return null
        val objects = mutableListOf<JsonObject>()
        when (element) {
            is JsonObject -> {
                element["outbounds"]?.asArray()?.forEach { objects += it }
                element["endpoints"]?.asArray()?.forEach { objects += it }
            }
            is JsonArray -> {
                element.forEach { if (it is JsonObject) objects += it }
            }
            else -> return null
        }
        if (objects.isEmpty()) return null

        val profiles = objects.mapNotNull { parseNode(it) }
        return ParseBatch(
            profiles = profiles,
            errors = if (profiles.isEmpty()) listOf("JSON найден, но поддерживаемых узлов нет") else emptyList(),
        )
    }

    private fun parseNode(node: JsonObject): ServerProfile? {
        val type = node.string("type").lowercase()
        val protocol = when (type) {
            "vless" -> Protocol.VLESS
            "vmess" -> Protocol.VMESS
            "trojan" -> Protocol.TROJAN
            "shadowsocks" -> Protocol.SHADOWSOCKS
            "hysteria2" -> Protocol.HYSTERIA2
            "tuic" -> Protocol.TUIC
            "wireguard" -> Protocol.WIREGUARD
            "socks" -> Protocol.SOCKS
            "http" -> Protocol.HTTP
            "ssh" -> Protocol.SSH
            else -> return null
        }
        val server = node.string("server")
        if (server.isBlank()) return null
        val tls = node["tls"] as? JsonObject
        val reality = tls?.get("reality") as? JsonObject
        val transport = node["transport"] as? JsonObject
        val peer = (node["peers"] as? JsonArray)?.firstOrNull() as? JsonObject

        val security = when {
            reality != null -> "reality"
            tls?.bool("enabled") == true -> "tls"
            else -> "none"
        }

        return ServerProfile(
            name = node.string("tag").ifBlank { "$server:${node.int("server_port") ?: 0}" },
            protocol = protocol,
            address = server,
            port = node.int("server_port") ?: protocol.defaultPort,
            uuid = node.string("uuid"),
            password = node.string("password").ifBlank { node.string("private_key") },
            username = node.string("username"),
            method = node.string("method").ifBlank { node.string("security") },
            flow = node.string("flow"),
            security = security,
            sni = tls?.string("server_name").orEmpty(),
            alpn = tls?.get("alpn")?.let { element ->
                runCatching { element.jsonArray.mapNotNull { (it as? JsonPrimitive)?.content } }.getOrNull()
            } ?: emptyList(),
            fingerprint = (tls?.get("utls") as? JsonObject)?.string("fingerprint").orEmpty(),
            publicKey = reality?.string("public_key").orEmpty(),
            shortId = reality?.string("short_id").orEmpty(),
            network = transport?.string("type")?.ifBlank { "tcp" } ?: "tcp",
            path = transport?.string("path").orEmpty(),
            host = transport?.get("host")?.let { element ->
                runCatching { element.jsonArray.firstOrNull()?.jsonPrimitive?.content }.getOrNull()
            } ?: transport?.string("host").orEmpty(),
            serviceName = transport?.string("service_name").orEmpty(),
            allowInsecure = tls?.bool("insecure") == true,
            privateKey = node.string("private_key"),
            peerPublicKey = peer?.string("public_key").orEmpty(),
            presharedKey = peer?.string("pre_shared_key").orEmpty(),
            localAddress = (node["address"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.content
                ?: "10.0.0.2/32",
            mtu = node.int("mtu") ?: 1420,
        )
    }

    private fun Any?.asArray(): List<JsonObject> =
        (this as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.content.orEmpty()

    private fun JsonObject.bool(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull == true

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull
}
