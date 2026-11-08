package com.openproxy.client.core.parse

import com.openproxy.client.core.model.Protocol
import com.openproxy.client.core.model.ServerProfile

object ClashYamlParser {

    fun isClashYaml(text: String): Boolean {
        val trimmed = text.trim()
        return Regex("(?im)^\\s*proxies\\s*:").containsMatchIn(trimmed) ||
            Regex("(?im)^\\s*proxy\\s*:").containsMatchIn(trimmed)
    }

    fun parse(text: String): ParseBatch {
        val lines = text.lines()
        val proxiesSectionIndex = lines.indexOfFirst { line ->
            val t = line.trim()
            t.equals("proxies:", ignoreCase = true) ||
                t.equals("proxy:", ignoreCase = true) ||
                t.startsWith("proxies:", ignoreCase = true)
        }
        if (proxiesSectionIndex < 0) {
            return ParseBatch(errors = listOf("Секция 'proxies' не найдена в Clash YAML"))
        }

        val rawBlocks = mutableListOf<List<String>>()
        var currentBlock: MutableList<String>? = null

        for (i in (proxiesSectionIndex + 1) until lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            // A new top-level section (e.g. proxy-groups:, rules:) starts at column 0 with colon
            if (!line.startsWith(" ") && !line.startsWith("\t") && trimmed.endsWith(":") && !trimmed.startsWith("-")) {
                break
            }

            if (trimmed.startsWith("-")) {
                currentBlock?.let { rawBlocks += it }
                currentBlock = mutableListOf(line)
            } else if (currentBlock != null) {
                currentBlock.add(line)
            }
        }
        currentBlock?.let { rawBlocks += it }

        val profiles = mutableListOf<ServerProfile>()
        val errors = mutableListOf<String>()

        for (block in rawBlocks) {
            val map = parseBlock(block)
            val profile = buildProfile(map)
            if (profile != null) {
                profiles += profile
            } else {
                val name = map["name"] ?: "узел"
                errors += "Не удалось разобрать узел Clash: $name"
            }
        }

        return ParseBatch(
            profiles = profiles,
            errors = if (profiles.isEmpty() && errors.isEmpty()) {
                listOf("В секции proxies не найдено подходящих серверов")
            } else {
                errors
            },
        )
    }

    private fun parseBlock(block: List<String>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var prefix = ""

        for (line in block) {
            var trimmed = line.trim()
            if (trimmed.startsWith("-")) {
                trimmed = trimmed.substring(1).trim()
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val colonIdx = trimmed.indexOf(':')
            if (colonIdx <= 0) continue

            val key = trimmed.substring(0, colonIdx).trim().trim('"', '\'')
            val value = trimmed.substring(colonIdx + 1).trim()

            if (value.isEmpty()) {
                // Nested block (e.g. reality-opts: or ws-opts:)
                prefix = "$key."
            } else {
                // If line indentation is at node root level, clear prefix
                val indent = line.takeWhile { it == ' ' || it == '\t' }.length
                if (indent <= 4 && !trimmed.startsWith("-")) {
                    prefix = ""
                }
                val cleanValue = value.trim('"', '\'', '`')
                result["$prefix$key"] = cleanValue
                result[key] = cleanValue // Also save without prefix for easy lookup
            }
        }
        return result
    }

    private fun buildProfile(map: Map<String, String>): ServerProfile? {
        val server = map["server"].orEmpty().ifBlank { map["add"].orEmpty() }
        if (server.isBlank()) return null
        val port = map["port"]?.toIntOrNull() ?: 443
        val name = map["name"].orEmpty().ifBlank { "$server:$port" }
        val type = map["type"]?.lowercase().orEmpty()

        val sni = map["servername"] ?: map["sni"] ?: map["peer"].orEmpty()
        val network = map["network"]?.lowercase() ?: "tcp"
        val tls = map["tls"]?.lowercase() == "true" || map["reality"]?.lowercase() == "true"
        val isReality = map["reality-opts.public-key"] != null || map["public-key"] != null

        val security = when {
            isReality -> "reality"
            tls -> "tls"
            else -> "none"
        }

        val alpnList = map["alpn"]?.split(',')
            ?.map { it.trim().trim('"', '\'', '[', ']') }
            ?.filter { it.isNotEmpty() } ?: emptyList()

        return when (type) {
            "vless" -> ServerProfile(
                name = name,
                protocol = Protocol.VLESS,
                address = server,
                port = port,
                uuid = map["uuid"].orEmpty(),
                flow = map["flow"].orEmpty(),
                security = security,
                sni = sni,
                alpn = alpnList,
                fingerprint = map["client-fingerprint"] ?: map["fingerprint"].orEmpty(),
                publicKey = map["reality-opts.public-key"] ?: map["public-key"].orEmpty(),
                shortId = map["reality-opts.short-id"] ?: map["short-id"].orEmpty(),
                network = network,
                path = map["ws-opts.path"] ?: map["path"].orEmpty(),
                host = map["ws-opts.headers.Host"] ?: map["ws-opts.headers.host"] ?: map["host"].orEmpty(),
                serviceName = map["grpc-opts.grpc-service-name"] ?: map["service-name"].orEmpty(),
                allowInsecure = map["skip-cert-verify"] == "true",
            )

            "vmess" -> ServerProfile(
                name = name,
                protocol = Protocol.VMESS,
                address = server,
                port = port,
                uuid = map["uuid"].orEmpty(),
                method = map["cipher"]?.ifBlank { "auto" } ?: "auto",
                security = if (tls) "tls" else "none",
                sni = sni,
                alpn = alpnList,
                fingerprint = map["client-fingerprint"] ?: map["fingerprint"].orEmpty(),
                network = network,
                path = map["ws-opts.path"] ?: map["path"].orEmpty(),
                host = map["ws-opts.headers.Host"] ?: map["ws-opts.headers.host"] ?: map["host"].orEmpty(),
                allowInsecure = map["skip-cert-verify"] == "true",
            )

            "trojan" -> ServerProfile(
                name = name,
                protocol = Protocol.TROJAN,
                address = server,
                port = port,
                password = map["password"].orEmpty(),
                security = "tls",
                sni = sni,
                alpn = alpnList,
                fingerprint = map["client-fingerprint"] ?: map["fingerprint"].orEmpty(),
                network = network,
                path = map["ws-opts.path"] ?: map["path"].orEmpty(),
                host = map["ws-opts.headers.Host"] ?: map["host"].orEmpty(),
                allowInsecure = map["skip-cert-verify"] == "true",
            )

            "ss", "shadowsocks" -> ServerProfile(
                name = name,
                protocol = Protocol.SHADOWSOCKS,
                address = server,
                port = port,
                method = map["cipher"].orEmpty(),
                password = map["password"].orEmpty(),
                plugin = map["plugin"].orEmpty(),
                pluginOptions = map["plugin-opts"]?.replace(';', '&').orEmpty(),
            )

            "hysteria2", "hy2" -> ServerProfile(
                name = name,
                protocol = Protocol.HYSTERIA2,
                address = server,
                port = port,
                password = map["password"].orEmpty(),
                security = "tls",
                sni = sni,
                alpn = alpnList.ifEmpty { listOf("h3") },
                obfs = map["obfs"].orEmpty(),
                obfsPassword = map["obfs-password"].orEmpty(),
                network = "quic",
                allowInsecure = map["skip-cert-verify"] == "true",
            )

            "tuic" -> ServerProfile(
                name = name,
                protocol = Protocol.TUIC,
                address = server,
                port = port,
                uuid = map["uuid"].orEmpty(),
                password = map["password"].orEmpty(),
                security = "tls",
                sni = sni,
                alpn = alpnList.ifEmpty { listOf("h3") },
                network = "quic",
                allowInsecure = map["skip-cert-verify"] == "true",
            )

            "wireguard" -> ServerProfile(
                name = name,
                protocol = Protocol.WIREGUARD,
                address = server,
                port = port,
                privateKey = map["private-key"].orEmpty(),
                peerPublicKey = map["public-key"].orEmpty(),
                presharedKey = map["preshared-key"].orEmpty(),
                localAddress = map["ip"] ?: "10.0.0.2/32",
                mtu = map["mtu"]?.toIntOrNull() ?: 1420,
            )

            else -> null
        }
    }
}
