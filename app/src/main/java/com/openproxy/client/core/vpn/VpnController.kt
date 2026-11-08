package com.openproxy.client.core.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.openproxy.client.core.model.VpnStatus
import kotlinx.coroutines.flow.StateFlow

object VpnController {

    val status: StateFlow<VpnStatus> = ProxyVpnService.status
    val logs: StateFlow<List<String>> = ProxyVpnService.logs

    /** Returns null when the user already granted VPN permission. */
    fun prepare(context: Context): Intent? = VpnService.prepare(context)

    fun start(context: Context, profileId: String? = null) {
        ContextCompat.startForegroundService(
            context,
            ProxyVpnService.connectIntent(context, profileId),
        )
    }

    fun stop(context: Context) {
        runCatching { context.startService(ProxyVpnService.disconnectIntent(context)) }
    }
}
