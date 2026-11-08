package com.openproxy.client.core.model

import kotlinx.serialization.Serializable

/**
 * Every protocol this client can recognise. [coreSupported] tells whether the bundled
 * sing-box core can actually tunnel it; unsupported profiles can still be imported,
 * inspected and exported, but the UI explains why they cannot be connected.
 */
@Serializable
enum class Protocol(
    val title: String,
    val defaultPort: Int,
    val coreSupported: Boolean,
    val coreNote: String = "",
) {
    VLESS("VLESS", 443, true),
    VMESS("VMess", 443, true),
    TROJAN("Trojan", 443, true),
    SHADOWSOCKS("Shadowsocks", 8388, true),
    SHADOWSOCKS_R(
        "ShadowsocksR",
        8388,
        false,
        "sing-box не поддерживает протокол SSR. Профиль сохранён, но подключение недоступно.",
    ),
    HYSTERIA2("Hysteria 2", 443, true),
    HYSTERIA(
        "Hysteria 1",
        443,
        false,
        "Ядро поддерживает только Hysteria 2. Для Hysteria 1 нужен другой клиент.",
    ),
    TUIC("TUIC", 443, true),
    WIREGUARD("WireGuard", 51820, true),
    AMNEZIA_WG(
        "AmneziaWG",
        51820,
        false,
        "В этой сборке ядра нет AmneziaWG-обфускации. Импорт сохранён, туннель недоступен.",
    ),
    OPENVPN(
        "OpenVPN",
        1194,
        false,
        "Файл .ovpn импортирован и сохранён. Автоматический запуск OpenVPN требует " +
            "отдельного ядра (встроенное ядро OpenVPN пока не подключено).",
    ),
    SOCKS("SOCKS5", 1080, true),
    HTTP("HTTP-прокси", 8080, true),
    SSH("SSH", 22, true),
    ;

    /** Protocols whose transport is UDP-first: a TCP handshake is only a rough reachability hint. */
    val udpFirst: Boolean
        get() = this == WIREGUARD || this == AMNEZIA_WG || this == HYSTERIA2 ||
            this == TUIC || this == OPENVPN
}
