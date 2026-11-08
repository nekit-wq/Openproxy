package com.openproxy.client.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
enum class RoutingMode {
    /** Everything goes through the selected server. */
    GLOBAL,

    /** Private/LAN networks stay direct, the rest goes through the server. */
    RULES,

    /** Nothing is tunnelled (useful for split-DNS diagnostics). */
    DIRECT,
}

@Serializable
enum class DnsMode(val title: String, val url: String) {
    SYSTEM("Системный DNS", ""),
    CLOUDFLARE("Cloudflare (HTTPS)", "https://1.1.1.1/dns-query"),
    GOOGLE("Google (HTTPS)", "https://8.8.8.8/dns-query"),
    ADGUARD("AdGuard (HTTPS)", "https://94.140.14.14/dns-query"),
    CUSTOM("Свой сервер", ""),
}

@Serializable
enum class PerAppMode { ALL, INCLUDE, EXCLUDE }

/**
 * All user-visible knobs. Defaults are conservative: private networks bypass the tunnel,
 * IPv6 is off (many mobile networks break it), and DNS goes through the tunnel.
 */
@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val routingMode: RoutingMode = RoutingMode.RULES,
    val bypassLan: Boolean = true,
    val ipv6Enabled: Boolean = false,
    val dnsMode: DnsMode = DnsMode.CLOUDFLARE,
    val customDns: String = "",
    val dnsThroughTunnel: Boolean = true,
    val mtu: Int = 9000,
    val perAppMode: PerAppMode = PerAppMode.ALL,
    val perAppPackages: Set<String> = emptySet(),
    val selectedProfileId: String? = null,
    val autoConnectOnLaunch: Boolean = false,
    val blockAds: Boolean = false,
    val udpEnabled: Boolean = true,
    val sniffEnabled: Boolean = true,
    val customRules: String = "",
    val logLevel: String = "info",
    val showLatencyInList: Boolean = true,
    val autoUpdateSubscriptions: Boolean = true,
    val language: String = "ru",
) {
    val dnsServer: String
        get() = if (dnsMode == DnsMode.CUSTOM) customDns.ifBlank { "1.1.1.1" } else dnsMode.url
}
