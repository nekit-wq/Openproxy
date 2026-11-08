package com.openproxy.client.core.singbox

import com.openproxy.client.core.model.AppSettings
import com.openproxy.client.core.model.Protocol
import com.openproxy.client.core.model.RoutingMode
import com.openproxy.client.core.model.ServerProfile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI

/**
 * Turns one [ServerProfile] plus user [AppSettings] into a complete sing-box configuration:
 * TUN inbound, DNS, the protocol outbound (or endpoint), routing rules and the local
 * Clash API used by the app for live traffic stats and URL-tests.
 */
object SingBoxConfigBuilder {

    const val PROXY_TAG = "proxy"
    private const val DIRECT_TAG = "direct"
    private const val TUN_ADDRESS_V4 = "172.19.0.1/30"
    private const val TUN_ADDRESS_V6 = "fdfe:dcba:9876::1/126"
    private const val ADS_RULESET = "geosite-ads"
    private const val ADS_RULESET_URL =
        "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ads-all.srs"

    fun build(
        profile: ServerProfile,
        settings: AppSettings,
        clashPort: Int = 0,
        clashSecret: String = "",
    ): String {
        require(profile.protocol.coreSupported) {
            profile.protocol.coreNote.ifBlank { "Протокол ${profile.protocol.title} не поддерживается ядром" }
        }
        if (profile.network == "xhttp") {
            error("Транспорт XHTTP требует Xray-ядро и пока не поддерживается встроенным sing-box")
        }
        val config = buildJsonObject {
            putJsonObject("log") {
                put("level", settings.logLevel)
                put("timestamp", true)
            }
            put("dns", buildDns(settings))
            put("inbounds", buildJsonArray { add(buildTunInbound(settings)) })
            if (profile.protocol == Protocol.WIREGUARD) {
                put("endpoints", buildJsonArray { add(buildWireGuardEndpoint(profile)) })
            } else {
                put("outbounds", buildJsonArray {
                    add(buildOutbound(profile))
                    add(buildJsonObject {
                        put("type", DIRECT_TAG)
                        put("tag", DIRECT_TAG)
                    })
                    add(buildJsonObject {
                        put("type", "block")
                        put("tag", "block")
                    })
                })
            }
            put("route", buildRoute(settings))
            put("experimental", buildExperimental(clashPort, clashSecret))
        }
        return config.toString()
    }

    // ------------------------------------------------------------- inbound

    private fun buildTunInbound(settings: AppSettings): JsonObject = buildJsonObject {
        put("type", "tun")
        put("tag", "tun-in")
        putJsonArray("address") {
            add(TUN_ADDRESS_V4)
            if (settings.ipv6Enabled) add(TUN_ADDRESS_V6)
        }
        put("mtu", settings.mtu.coerceIn(1280, 9000))
        put("stack", "mixed")
        put("auto_route", true)
        put("strict_route", true)
    }

    // ----------------------------------------------------------------- DNS

    private fun buildDns(settings: AppSettings): JsonObject = buildJsonObject {
        put("servers", buildJsonArray {
            add(buildJsonObject {
                put("type", "local")
                put("tag", "local")
            })
            if (settings.dnsServer.isNotBlank()) {
                add(buildRemoteDns(settings))
            }
        })
        val useRemote = settings.dnsServer.isNotBlank()
        put("final", if (useRemote) "remote" else "local")
        if (settings.ipv6Enabled) {
            put("strategy", "prefer_ipv4")
        }
    }

    private fun buildRemoteDns(settings: AppSettings): JsonObject {
        val raw = settings.dnsServer.trim()
        val uri = runCatching { URI(if ("://" in raw) raw else "udp://$raw") }.getOrNull()
        val host = uri?.host ?: raw.substringAfter("://").substringBefore('/').substringBefore(':')
        return buildJsonObject {
            when {
                raw.startsWith("https://") -> {
                    put("type", "https")
                    put("tag", "remote")
                    put("server", host)
                }

                raw.startsWith("tls://") -> {
                    put("type", "tls")
                    put("tag", "remote")
                    put("server", host)
                }

                else -> {
                    put("type", "udp")
                    put("tag", "remote")
                    put("server", host)
                }
            }
            if (settings.dnsThroughTunnel && settings.routingMode != RoutingMode.DIRECT) {
                put("detour", PROXY_TAG)
            }
        }
    }

    // --------------------------------------------------------------- route

    private fun buildRoute(settings: AppSettings): JsonObject = buildJsonObject {
        put("rules", buildJsonArray {
            if (settings.sniffEnabled) {
                add(buildJsonObject { put("action", "sniff") })
            }
            add(buildJsonObject {
                put("protocol", "dns")
                put("action", "hijack-dns")
            })
            if (settings.bypassLan) {
                add(buildJsonObject {
                    put("ip_is_private", true)
                    put("action", "route")
                    put("outbound", DIRECT_TAG)
                })
            }
            if (settings.blockAds) {
                add(buildJsonObject {
                    put("rule_set", ADS_RULESET)
                    put("action", "reject")
                })
            }
            for (rule in parseCustomRules(settings.customRules)) {
                add(rule)
            }
        })
        put(
            "final",
            if (settings.routingMode == RoutingMode.DIRECT) DIRECT_TAG else PROXY_TAG,
        )
        // Required since sing-box 1.14: how to resolve domain names in outbound dial fields.
        put("default_domain_resolver", "local")
        put("auto_detect_interface", true)
        if (settings.blockAds) {
            put("rule_set", buildJsonArray {
                add(buildJsonObject {
                    put("type", "remote")
                    put("tag", ADS_RULESET)
                    put("format", "binary")
                    put("url", ADS_RULESET_URL)
                    if (settings.routingMode != RoutingMode.DIRECT) {
                        put("download_detour", PROXY_TAG)
                    }
                })
            })
        }
    }

    /**
     * User rules, one per line:
     * `domain:example.com=direct`, `domain_suffix:example.org=proxy`,
     * `ip:10.0.0.0/8=direct`, `port:25=block`, `process:com.app=direct`.
     */
    internal fun parseCustomRules(raw: String): List<JsonObject> {
        if (raw.isBlank()) return emptyList()
        return raw.lines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
            val separator = trimmed.lastIndexOf('=')
            if (separator <= 0) return@mapNotNull null
            val condition = trimmed.substring(0, separator).trim()
            val target = trimmed.substring(separator + 1).trim().lowercase()
            val kind = condition.substringBefore(':', "").trim().lowercase()
            val value = condition.substringAfter(':', "").trim()
            if (value.isEmpty()) return@mapNotNull null

            buildJsonObject {
                when (kind) {
                    "domain" -> putJsonArray("domain") { add(value) }
                    "suffix", "domain_suffix" -> putJsonArray("domain_suffix") { add(value) }
                    "keyword", "domain_keyword" -> putJsonArray("domain_keyword") { add(value) }
                    "ip", "ip_cidr" -> putJsonArray("ip_cidr") { add(value) }
                    "port" -> put("port", value.toIntOrNull() ?: return@mapNotNull null)
                    "process", "process_name", "package" -> putJsonArray("process_name") { add(value) }
                    else -> return@mapNotNull null
                }
                when (target) {
                    "direct", "напрямую" -> {
                        put("action", "route")
                        put("outbound", DIRECT_TAG)
                    }

                    "block", "reject", "блок" -> put("action", "reject")
                    else -> {
                        put("action", "route")
                        put("outbound", PROXY_TAG)
                    }
                }
            }
        }
    }

    private fun buildExperimental(clashPort: Int, secret: String): JsonObject = buildJsonObject {
        putJsonObject("cache_file") { put("enabled", true) }
        if (clashPort > 0) {
            putJsonObject("clash_api") {
                put("external_controller", "127.0.0.1:$clashPort")
                if (secret.isNotBlank()) put("secret", secret)
            }
        }
    }

    // ------------------------------------------------------------ outbounds

    private fun buildOutbound(profile: ServerProfile): JsonObject = buildJsonObject {
        when (profile.protocol) {
            Protocol.VLESS -> {
                put("type", "vless")
                put("tag", PROXY_TAG)
                put("server", profile.address)
                put("server_port", profile.port)
                put("uuid", profile.uuid)
                if (profile.flow.isNotBlank()) put("flow", profile.flow)
                put("packet_encoding", "xudp")
                buildTls(profile)?.let { put("tls", it) }
                buildTransport(profile)?.let { put("transport", it) }
            }

            Protocol.VMESS -> {
                put("type", "vmess")
                put("tag", PROXY_TAG)
                put("server", profile.address)
                put("server_port", profile.port)
                put("uuid", profile.uuid)
                put("security", profile.method.ifBlank { "auto" })
                put("alter_id", 0)
                buildTls(profile)?.let { put("tls", it) }
                buildTransport(profile)?.let { put("transport", it) }
            }

            Protocol.TROJAN -> {
                put("type", "trojan")
                put("tag", PROXY_TAG)
                put("server", profile.address)
                put("server_port", profile.port)
                put("password", profile.password)
                buildTls(profile)?.let { put("tls", it) }
                buildTransport(profile)?.let { put("transport", it) }
            }

            Protocol.SHADOWSOCKS -> {
                put("type", "shadowsocks")
                put("tag", PROXY_TAG)
                put("server", profile.address)
                put("server_port", profile.port)
                put("method", profile.method.ifBlank { "aes-256-gcm" })
                put("password", profile.password)
                if (profile.plugin.isNotBlank()) {
                    put("plugin", profile.plugin)
                    if (profile.pluginOptions.isNotBlank()) put("plugin_opts", profile.pluginOptions)
                }
            }

            Protocol.HYSTERIA2 -> {
                put("type", "hysteria2")
                put("tag", PROXY_TAG)
                put("server", profile.address)
                put("server_port", profile.port)
                put("password", profile.password)
                if (profile.obfs.isNotBlank()) {
                    putJsonObject("obfs") {
                        put("type", profile.obfs)
                        if (profile.obfsPassword.isNotBlank()) put("password", profile.obfsPassword)
                    }
                }
                put("tls", buildTls(profile) ?: defaultTls(profile))
            }

            Protocol.TUIC -> {
                put("type", "tuic")
                put("tag", PROXY_TAG)
                put("server", profile.address)
                put("server_port", profile.port)
                put("uuid", profile.uuid)
                put("password", profile.password)
                put("congestion_control", profile.plugin.ifBlank { "bbr" })
                put("udp_relay_mode", profile.pluginOptions.ifBlank { "native" })
                put("zero_rtt_handshake", false)
                put("tls", buildTls(profile) ?: defaultTls(profile))
            }

            Protocol.SOCKS -> {
                put("type", "socks")
                put("tag", PROXY_TAG)
                put("server", profile.address)
                put("server_port", profile.port)
                put("version", "5")
                if (profile.username.isNotBlank()) put("username", profile.username)
                if (profile.password.isNotBlank()) put("password", profile.password)
            }

            Protocol.HTTP -> {
                put("type", "http")
                put("tag", PROXY_TAG)
                put("server", profile.address)
                put("server_port", profile.port)
                if (profile.username.isNotBlank()) put("username", profile.username)
                if (profile.password.isNotBlank()) put("password", profile.password)
                if (profile.security == "tls") put("tls", defaultTls(profile))
            }

            Protocol.SSH -> {
                put("type", "ssh")
                put("tag", PROXY_TAG)
                put("server", profile.address)
                put("server_port", profile.port)
                put("user", profile.username.ifBlank { "root" })
                if (profile.password.isNotBlank()) put("password", profile.password)
            }

            Protocol.WIREGUARD -> Unit

            else -> error(profile.protocol.coreNote)
        }
    }

    private fun buildTls(profile: ServerProfile): JsonObject? {
        if (profile.security !in setOf("tls", "reality")) return null
        return buildJsonObject {
            put("enabled", true)
            put(
                "server_name",
                profile.sni.ifBlank { profile.host.ifBlank { profile.address } },
            )
            if (profile.allowInsecure) put("insecure", true)
            if (profile.alpn.isNotEmpty()) {
                put("alpn", buildJsonArray { profile.alpn.forEach { add(it) } })
            }
            if (profile.fingerprint.isNotBlank()) {
                putJsonObject("utls") {
                    put("enabled", true)
                    put("fingerprint", profile.fingerprint)
                }
            }
            if (profile.security == "reality") {
                putJsonObject("reality") {
                    put("enabled", true)
                    put("public_key", profile.publicKey)
                    if (profile.shortId.isNotBlank()) put("short_id", profile.shortId)
                }
                if (profile.fingerprint.isBlank()) {
                    putJsonObject("utls") {
                        put("enabled", true)
                        put("fingerprint", "chrome")
                    }
                }
            }
        }
    }

    private fun defaultTls(profile: ServerProfile): JsonObject = buildJsonObject {
        put("enabled", true)
        put("server_name", profile.sni.ifBlank { profile.host.ifBlank { profile.address } })
        if (profile.allowInsecure) put("insecure", true)
        if (profile.alpn.isNotEmpty()) {
            put("alpn", buildJsonArray { profile.alpn.forEach { add(it) } })
        }
    }

    private fun buildTransport(profile: ServerProfile): JsonObject? {
        return when (profile.network) {
            "ws" -> buildJsonObject {
                put("type", "ws")
                put("path", profile.path.ifBlank { "/" })
                if (profile.host.isNotBlank()) {
                    putJsonObject("headers") { put("Host", profile.host) }
                }
                put("max_early_data", 2048)
                put("early_data_header_name", "Sec-WebSocket-Protocol")
            }

            "grpc" -> buildJsonObject {
                put("type", "grpc")
                put(
                    "service_name",
                    profile.serviceName.ifBlank { profile.path.trim('/') },
                )
            }

            "http" -> buildJsonObject {
                put("type", "http")
                if (profile.host.isNotBlank()) {
                    putJsonArray("host") { add(profile.host) }
                }
                put("path", profile.path.ifBlank { "/" })
            }

            "httpupgrade" -> buildJsonObject {
                put("type", "httpupgrade")
                if (profile.host.isNotBlank()) put("host", profile.host)
                put("path", profile.path.ifBlank { "/" })
            }

            "quic" -> buildJsonObject { put("type", "quic") }

            else -> null
        }
    }

    private fun buildWireGuardEndpoint(profile: ServerProfile): JsonObject = buildJsonObject {
        put("type", "wireguard")
        put("tag", PROXY_TAG)
        put("address", buildJsonArray {
            profile.localAddress.split(',').map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { add(it) }
        })
        put("private_key", profile.privateKey)
        if (profile.mtu > 0) put("mtu", profile.mtu)
        put("peers", buildJsonArray {
            addJsonObject {
                put("address", profile.address)
                put("port", profile.port)
                put("public_key", profile.peerPublicKey)
                if (profile.presharedKey.isNotBlank()) put("pre_shared_key", profile.presharedKey)
                put("allowed_ips", buildJsonArray {
                    profile.allowedIps.split(',').map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .forEach { add(it) }
                })
                if (profile.keepalive > 0) put("persistent_keepalive_interval", profile.keepalive)
                if (profile.reserved.size == 3) {
                    put("reserved", buildJsonArray { profile.reserved.forEach { add(it) } })
                }
            }
        })
    }

    @Suppress("unused")
    private fun JsonObjectBuilder.stringArray(name: String, values: List<String>) {
        put(name, JsonArray(values.map { kotlinx.serialization.json.JsonPrimitive(it) }))
    }
}
