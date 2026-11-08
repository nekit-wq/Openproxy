package com.openproxy.client.core.parse

import com.openproxy.client.core.model.Protocol
import com.openproxy.client.core.model.ServerProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

data class ParseBatch(
    val profiles: List<ServerProfile> = emptyList(),
    val errors: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = profiles.isEmpty()
}

/**
 * Parses every share-link format the app supports:
 * vless (REALITY/TLS/WS/gRPC/HTTP/HTTPUpgrade), vmess, trojan, ss, ssr, hysteria/hysteria2,
 * tuic, wireguard/awg, socks, ssh and openvpn (as a saved .ovpn).
 */
object ShareLinkParser {

    private val schemes = setOf(
        "vless", "vmess", "trojan", "ss", "ssr", "hysteria", "hysteria2", "hy2",
        "tuic", "wireguard", "wg", "awg", "socks", "socks5", "ssh", "openvpn",
    )

    fun looksLikeShareLink(text: String): Boolean {
        val scheme = text.trim().substringBefore("://", "").lowercase()
        return scheme in schemes && text.contains("://")
    }

    /** Parses a block of links, a base64 subscription body, or a raw config file. */
    fun parseMany(text: String): ParseBatch {
        val errors = mutableListOf<String>()
        var payload = text.trim().removePrefix("\uFEFF")
        if (payload.isEmpty()) return ParseBatch(errors = listOf("Пустой текст для импорта"))

        if (Base64Codec.looksLikeBase64(payload)) {
            Base64Codec.decode(payload)?.let { payload = it.trim() }
        }

        if (WireGuardConfigParser.isWireGuardConfig(payload)) {
            val profile = WireGuardConfigParser.parse(payload)
            return if (profile != null) {
                ParseBatch(listOf(profile))
            } else {
                ParseBatch(errors = listOf("Не удалось разобрать WireGuard/AmneziaWG конфиг"))
            }
        }

        if (OpenVpnConfigParser.isOpenVpnConfig(payload)) {
            val profile = OpenVpnConfigParser.parse(payload)
            return if (profile != null) {
                ParseBatch(listOf(profile))
            } else {
                ParseBatch(errors = listOf("Не удалось разобрать OpenVPN конфиг"))
            }
        }

        if (payload.startsWith("{")) {
            SingBoxJsonParser.parse(payload)?.let { return it }
        }

        val profiles = mutableListOf<ServerProfile>()
        val lines = payload.split('\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }

        for (line in lines) {
            if (!line.contains("://")) {
                errors += "Пропущена строка: ${line.take(48)}…"
                continue
            }
            val profile = parseLink(line)
            if (profile != null) {
                profiles += profile
            } else {
                errors += "Не распознана ссылка: ${line.take(64)}…"
            }
        }
        return ParseBatch(profiles, errors)
    }

    fun parseLink(raw: String): ServerProfile? {
        val link = raw.trim()
        if (link.isEmpty() || !link.contains("://")) return null
        return runCatching {
            when (link.substringBefore("://").lowercase()) {
                "vless" -> parseVless(link)
                "vmess" -> parseVmess(link)
                "trojan" -> parseTrojan(link)
                "ss" -> parseShadowsocks(link)
                "ssr" -> parseShadowsocksR(link)
                "hysteria2", "hy2" -> parseHysteria2(link)
                "hysteria" -> parseHysteria1(link)
                "tuic" -> parseTuic(link)
                "wireguard", "wg" -> parseWireGuard(link, amnezia = false)
                "awg" -> parseWireGuard(link, amnezia = true)
                "socks", "socks5" -> parseSocks(link)
                "ssh" -> parseSsh(link)
                "openvpn" -> parseOpenVpnUri(link)
                "http", "https" -> parseHttpProxy(link)
                else -> null
            }
        }.getOrNull()
    }

    // ---------------------------------------------------------------- VLESS

    private fun parseVless(link: String): ServerProfile? {
        val parts = LinkUtil.parse(link) ?: return null
        if (parts.userInfo.isBlank() || parts.address.isBlank()) return null
        val q = parts.query
        val security = q["security"]?.lowercase()?.ifBlank { "none" } ?: "none"
        val network = normalizeNetwork(q["type"])
        val sni = q["sni"] ?: q["peer"] ?: ""
        return ServerProfile(
            name = parts.fragment,
            protocol = Protocol.VLESS,
            address = parts.address,
            port = parts.port.takeIf { it > 0 } ?: 443,
            uuid = parts.userInfo,
            flow = q["flow"].orEmpty(),
            security = security,
            sni = sni,
            alpn = splitAlpn(q["alpn"]),
            fingerprint = q["fp"].orEmpty(),
            publicKey = q["pbk"].orEmpty(),
            shortId = q["sid"].orEmpty(),
            spiderX = q["spx"].orEmpty(),
            network = network,
            headerType = q["headerType"]?.ifBlank { "none" } ?: "none",
            path = q["path"].orEmpty(),
            host = q["host"].orEmpty(),
            serviceName = q["serviceName"].orEmpty(),
            allowInsecure = q["allowInsecure"]?.truthy() == true || q["insecure"]?.truthy() == true,
            rawLink = link,
        )
    }

    // ---------------------------------------------------------------- VMess

    private fun parseVmess(link: String): ServerProfile? {
        val payload = link.substringAfter("://").substringBefore('#').trim()
        val decoded = Base64Codec.decode(payload)
        if (decoded != null && decoded.trimStart().startsWith("{")) {
            val obj = runCatching { Json.parseToJsonElement(decoded).jsonObject }.getOrNull()
            if (obj != null) return fromVmessJson(obj, link)
        }
        // Rare plain-URI vmess form.
        val parts = LinkUtil.parse(link) ?: return null
        if (parts.userInfo.isBlank()) return null
        val q = parts.query
        return ServerProfile(
            name = parts.fragment,
            protocol = Protocol.VMESS,
            address = parts.address,
            port = parts.port.takeIf { it > 0 } ?: 443,
            uuid = parts.userInfo,
            method = q["encryption"] ?: q["scy"] ?: "auto",
            security = if (q["tls"]?.truthy() == true) "tls" else "none",
            sni = q["sni"].orEmpty(),
            network = normalizeNetwork(q["type"]),
            path = q["path"].orEmpty(),
            host = q["host"].orEmpty(),
            alpn = splitAlpn(q["alpn"]),
            fingerprint = q["fp"].orEmpty(),
            rawLink = link,
        )
    }

    private fun fromVmessJson(obj: JsonObject, link: String): ServerProfile? {
        fun g(key: String): String =
            (obj[key] as? JsonPrimitive)?.content?.trim().orEmpty()

        val address = g("add")
        if (address.isBlank()) return null
        val port = g("port").toIntOrNull() ?: 443
        val tls = g("tls").lowercase()
        val network = normalizeNetwork(g("net"))
        val headerHost = g("host")
        val sni = g("sni").ifBlank { headerHost }
        return ServerProfile(
            name = g("ps"),
            protocol = Protocol.VMESS,
            address = address,
            port = port,
            uuid = g("id"),
            method = g("scy").ifBlank { "auto" },
            security = if (tls == "tls" || tls == "reality" || tls == "true") "tls" else "none",
            sni = sni,
            alpn = splitAlpn(g("alpn")),
            fingerprint = g("fp"),
            network = network,
            headerType = g("type").ifBlank { "none" },
            path = g("path"),
            host = headerHost,
            allowInsecure = g("allowInsecure").truthy(),
            rawLink = link,
        )
    }

    // ---------------------------------------------------------------- Trojan

    private fun parseTrojan(link: String): ServerProfile? {
        val parts = LinkUtil.parse(link) ?: return null
        if (parts.userInfo.isBlank()) return null
        val q = parts.query
        return ServerProfile(
            name = parts.fragment,
            protocol = Protocol.TROJAN,
            address = parts.address,
            port = parts.port.takeIf { it > 0 } ?: 443,
            password = parts.userInfo,
            security = q["security"]?.lowercase()?.ifBlank { "tls" } ?: "tls",
            sni = q["sni"] ?: q["peer"] ?: "",
            alpn = splitAlpn(q["alpn"]),
            fingerprint = q["fp"].orEmpty(),
            network = normalizeNetwork(q["type"]),
            headerType = q["headerType"]?.ifBlank { "none" } ?: "none",
            path = q["path"].orEmpty(),
            host = q["host"].orEmpty(),
            serviceName = q["serviceName"].orEmpty(),
            allowInsecure = q["allowInsecure"]?.truthy() == true,
            rawLink = link,
        )
    }

    // ---------------------------------------------------------- Shadowsocks

    private fun parseShadowsocks(link: String): ServerProfile? {
        var body = link.substringAfter("://")
        var name = ""
        val hash = body.indexOf('#')
        if (hash >= 0) {
            name = percentDecode(body.substring(hash + 1))
            body = body.substring(0, hash)
        }
        var query = emptyMap<String, String>()
        val question = body.indexOf('?')
        if (question >= 0) {
            query = LinkUtil.parseQuery(body.substring(question + 1))
            body = body.substring(0, question)
        }
        body = body.trim().trimEnd('/')

        val at = body.lastIndexOf('@')
        if (at < 0) {
            // Legacy: ss://base64(method:password@host:port)
            val decoded = Base64Codec.decode(body) ?: return null
            val match = Regex("^(.+?):(.+)@(.+):(\\d+)$").find(decoded) ?: return null
            return ServerProfile(
                name = name,
                protocol = Protocol.SHADOWSOCKS,
                address = match.groupValues[3].removeSurrounding("[", "]"),
                port = match.groupValues[4].toIntOrNull() ?: 8388,
                method = match.groupValues[1],
                password = match.groupValues[2],
                rawLink = link,
            )
        }

        val userInfoRaw = body.substring(0, at)
        val hostPort = body.substring(at + 1)
        val decodedInfo = Base64Codec.decode(userInfoRaw)?.takeIf { it.contains(':') }
        val credentials = decodedInfo ?: percentDecode(userInfoRaw)
        val split = credentials.indexOf(':')
        if (split <= 0) return null
        val method = credentials.substring(0, split)
        val password = credentials.substring(split + 1)
        val hp = splitHostPort(hostPort) ?: return null
        val pluginRaw = query["plugin"].orEmpty()
        val plugin = if (pluginRaw.isBlank()) "" else pluginRaw.substringBefore(';')
        val pluginOptions = if (pluginRaw.isBlank()) "" else pluginRaw.substringAfter(';', "")
        return ServerProfile(
            name = name,
            protocol = Protocol.SHADOWSOCKS,
            address = hp.first,
            port = hp.second.takeIf { it > 0 } ?: 8388,
            method = method,
            password = password,
            plugin = plugin,
            pluginOptions = pluginOptions.replace(';', '&'),
            rawLink = link,
        )
    }

    private fun parseShadowsocksR(link: String): ServerProfile? {
        val decoded = Base64Codec.decode(link.substringAfter("://")) ?: return null
        val main = decoded.substringBefore("/?")
        val params = if (decoded.contains("/?")) LinkUtil.parseQuery(decoded.substringAfter("/?")) else emptyMap()
        val parts = main.split(':')
        if (parts.size < 6) return null
        val password = Base64Codec.decode(parts[parts.size - 1]).orEmpty()
        val obfs = parts[parts.size - 2]
        val method = parts[parts.size - 3]
        val protocolName = parts[parts.size - 4]
        val port = parts[parts.size - 5].toIntOrNull() ?: 8388
        val host = parts.dropLast(5).joinToString(":")
        val name = params["remarks"]?.let { Base64Codec.decode(it) ?: percentDecode(it) }.orEmpty()
        return ServerProfile(
            name = name,
            protocol = Protocol.SHADOWSOCKS_R,
            address = host,
            port = port,
            method = method,
            password = password,
            plugin = protocolName,
            pluginOptions = buildString {
                append("obfs=").append(obfs)
                params["obfsparam"]?.let { append("&obfs-param=").append(Base64Codec.decode(it) ?: it) }
                params["protoparam"]?.let { append("&proto-param=").append(Base64Codec.decode(it) ?: it) }
            },
            rawLink = link,
        )
    }

    // ------------------------------------------------------------ Hysteria

    private fun parseHysteria2(link: String): ServerProfile? {
        val parts = LinkUtil.parse(link) ?: return null
        val q = parts.query
        return ServerProfile(
            name = parts.fragment,
            protocol = Protocol.HYSTERIA2,
            address = parts.address,
            port = parts.port.takeIf { it > 0 } ?: 443,
            password = parts.userInfo,
            security = "tls",
            sni = q["sni"] ?: q["peer"] ?: "",
            alpn = splitAlpn(q["alpn"]),
            allowInsecure = q["insecure"]?.truthy() == true || q["allowInsecure"]?.truthy() == true,
            obfs = q["obfs"].orEmpty(),
            obfsPassword = q["obfs-password"] ?: q["obfs_password"] ?: "",
            network = "quic",
            rawLink = link,
        )
    }

    private fun parseHysteria1(link: String): ServerProfile? {
        val parts = LinkUtil.parse(link) ?: return null
        val q = parts.query
        return ServerProfile(
            name = parts.fragment,
            protocol = Protocol.HYSTERIA,
            address = parts.address,
            port = parts.port.takeIf { it > 0 } ?: 443,
            password = q["auth"].orEmpty(),
            security = "tls",
            sni = q["peer"] ?: q["sni"] ?: "",
            alpn = splitAlpn(q["alpn"]),
            allowInsecure = q["insecure"]?.truthy() == true,
            obfs = q["obfs"].orEmpty(),
            network = "quic",
            rawLink = link,
        )
    }

    // ---------------------------------------------------------------- TUIC

    private fun parseTuic(link: String): ServerProfile? {
        val parts = LinkUtil.parse(link) ?: return null
        val q = parts.query
        val colon = parts.userInfo.indexOf(':')
        val uuid = if (colon > 0) parts.userInfo.substring(0, colon) else parts.userInfo
        val password = if (colon > 0) parts.userInfo.substring(colon + 1) else ""
        return ServerProfile(
            name = parts.fragment,
            protocol = Protocol.TUIC,
            address = parts.address,
            port = parts.port.takeIf { it > 0 } ?: 443,
            uuid = uuid,
            password = password,
            security = "tls",
            sni = q["sni"].orEmpty(),
            alpn = splitAlpn(q["alpn"]) .ifEmpty { listOf("h3") },
            allowInsecure = q["allow_insecure"]?.truthy() == true || q["insecure"]?.truthy() == true,
            network = "quic",
            plugin = q["congestion_control"] ?: "bbr",
            pluginOptions = q["udp_relay_mode"] ?: "native",
            rawLink = link,
        )
    }

    // ----------------------------------------------------------- WireGuard

    private fun parseWireGuard(link: String, amnezia: Boolean): ServerProfile? {
        val parts = LinkUtil.parse(link) ?: return null
        if (parts.userInfo.isBlank()) return null
        val q = parts.query
        val awgKeys = listOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4")
        val awg = buildMap {
            for (key in awgKeys) q[key]?.let { put(key, it) }
        }
        return ServerProfile(
            name = parts.fragment,
            protocol = if (amnezia || awg.isNotEmpty()) Protocol.AMNEZIA_WG else Protocol.WIREGUARD,
            address = parts.address,
            port = parts.port.takeIf { it > 0 } ?: 51820,
            privateKey = parts.userInfo,
            peerPublicKey = q["publickey"] ?: q["publicKey"] ?: "",
            presharedKey = q["presharedkey"] ?: q["presharedKey"] ?: "",
            localAddress = q["address"] ?: q["ip"] ?: "10.0.0.2/32",
            allowedIps = q["allowedips"] ?: q["allowed_ips"] ?: "0.0.0.0/0, ::/0",
            dns = q["dns"].orEmpty(),
            mtu = q["mtu"]?.toIntOrNull() ?: 1420,
            keepalive = q["keepalive"]?.toIntOrNull() ?: q["persistentkeepalive"]?.toIntOrNull() ?: 25,
            reserved = q["reserved"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList(),
            awg = awg,
            network = "udp",
            rawLink = link,
        )
    }

    // --------------------------------------------------------------- SOCKS

    private fun parseSocks(link: String): ServerProfile? {
        val parts = LinkUtil.parse(link) ?: return null
        val decoded = Base64Codec.decode(parts.userInfo)?.takeIf { it.contains(':') }
        val credentials = decoded ?: parts.userInfo
        val colon = credentials.indexOf(':')
        return ServerProfile(
            name = parts.fragment,
            protocol = Protocol.SOCKS,
            address = parts.address,
            port = parts.port.takeIf { it > 0 } ?: 1080,
            username = if (colon > 0) credentials.substring(0, colon) else "",
            password = if (colon > 0) credentials.substring(colon + 1) else "",
            rawLink = link,
        )
    }

    private fun parseHttpProxy(link: String): ServerProfile? {
        val parts = LinkUtil.parse(link) ?: return null
        val colon = parts.userInfo.indexOf(':')
        return ServerProfile(
            name = parts.fragment,
            protocol = Protocol.HTTP,
            address = parts.address,
            port = parts.port.takeIf { it > 0 } ?: 8080,
            username = if (colon > 0) parts.userInfo.substring(0, colon) else "",
            password = if (colon > 0) parts.userInfo.substring(colon + 1) else "",
            security = if (parts.scheme == "https") "tls" else "none",
            rawLink = link,
        )
    }

    private fun parseSsh(link: String): ServerProfile? {
        val parts = LinkUtil.parse(link) ?: return null
        val colon = parts.userInfo.indexOf(':')
        return ServerProfile(
            name = parts.fragment,
            protocol = Protocol.SSH,
            address = parts.address,
            port = parts.port.takeIf { it > 0 } ?: 22,
            username = if (colon > 0) parts.userInfo.substring(0, colon) else parts.userInfo,
            password = if (colon > 0) parts.userInfo.substring(colon + 1) else "",
            rawLink = link,
        )
    }

    private fun parseOpenVpnUri(link: String): ServerProfile? {
        val payload = link.substringAfter("://").substringBefore('#')
        val decoded = Base64Codec.decode(payload) ?: return null
        val parsed = OpenVpnConfigParser.parse(decoded) ?: return null
        val fragment = percentDecode(link.substringAfter('#', ""))
        return parsed.copy(
            name = fragment.ifBlank { parsed.name },
            rawLink = link,
        )
    }

    // ------------------------------------------------------------- helpers

    private fun normalizeNetwork(raw: String?): String {
        return when (raw?.trim()?.lowercase()) {
            null, "", "tcp", "raw" -> "tcp"
            "ws", "websocket" -> "ws"
            "grpc", "gun" -> "grpc"
            "h2", "http" -> "http"
            "httpupgrade" -> "httpupgrade"
            "quic" -> "quic"
            "xhttp", "splithttp" -> "xhttp"
            else -> "tcp"
        }
    }

    private fun splitAlpn(raw: String?): List<String> =
        raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    private fun splitHostPort(value: String): Pair<String, Int>? {
        val text = value.trim().trimEnd('/')
        if (text.startsWith("[")) {
            val close = text.indexOf(']')
            if (close < 0) return null
            val port = text.substring(close + 1).removePrefix(":").toIntOrNull() ?: 0
            return text.substring(1, close) to port
        }
        val colon = text.lastIndexOf(':')
        if (colon < 0) return text to 0
        return text.substring(0, colon) to (text.substring(colon + 1).toIntOrNull() ?: 0)
    }
}
