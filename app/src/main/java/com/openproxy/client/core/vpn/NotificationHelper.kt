package com.openproxy.client.core.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.openproxy.client.MainActivity
import com.openproxy.client.R
import com.openproxy.client.core.model.VpnStatus

object NotificationHelper {

    const val CHANNEL_ID = "vpn_status"
    const val NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Состояние VPN",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Постоянное уведомление о работе туннеля"
                    setShowBadge(false)
                },
            )
        }
    }

    fun build(context: Context, status: VpnStatus): Notification {
        ensureChannel(context)
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnectIntent = PendingIntent.getService(
            context,
            1,
            ProxyVpnService.disconnectIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (status.state) {
            com.openproxy.client.core.model.ConnectionState.CONNECTED ->
                "${status.profileName} · ${status.protocol}"
            com.openproxy.client.core.model.ConnectionState.CONNECTING -> "Подключение…"
            com.openproxy.client.core.model.ConnectionState.ERROR -> status.error ?: "Ошибка подключения"
            else -> "Отключено"
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle("OpenProxy")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Отключить", disconnectIntent)
            .build()
    }
}
