package com.openproxy.client.core.model

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR }

/** Live state of the tunnel, observed by the UI and the foreground notification. */
data class VpnStatus(
    val state: ConnectionState = ConnectionState.DISCONNECTED,
    val profileId: String? = null,
    val profileName: String = "",
    val protocol: String = "",
    val since: Long = 0L,
    val uplink: Long = 0L,
    val downlink: Long = 0L,
    val uplinkTotal: Long = 0L,
    val downlinkTotal: Long = 0L,
    val error: String? = null,
    val coreVersion: String = "",
) {
    val isActive: Boolean
        get() = state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING
}
