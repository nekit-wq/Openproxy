package com.openproxy.client.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** A remote subscription plus the quota metadata advertised by its provider. */
@Serializable
data class Subscription(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val updatedAt: Long = 0L,
    val updateIntervalHours: Int = 12,
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val expiresAt: Long = 0L,
    val lastError: String = "",
    val autoUpdate: Boolean = true,
    val profileCount: Int = 0,
    val webPageUrl: String = "",
) {
    val usedBytes: Long get() = uploadBytes + downloadBytes

    val remainingBytes: Long
        get() = if (totalBytes > 0L) (totalBytes - usedBytes).coerceAtLeast(0L) else 0L

    val usageFraction: Float
        get() = if (totalBytes > 0L) {
            (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val isExpired: Boolean
        get() = expiresAt > 0L && expiresAt * 1000L < System.currentTimeMillis()

    val daysLeft: Long
        get() = if (expiresAt <= 0L) -1L else {
            val diff = expiresAt * 1000L - System.currentTimeMillis()
            if (diff <= 0L) 0L else diff / 86_400_000L
        }
}
