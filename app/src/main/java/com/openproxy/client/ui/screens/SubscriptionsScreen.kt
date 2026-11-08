package com.openproxy.client.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openproxy.client.core.model.Subscription
import com.openproxy.client.core.util.Format
import com.openproxy.client.ui.components.EmptyState
import com.openproxy.client.ui.components.ScreenHeader

@Composable
fun SubscriptionsScreen(
    subscriptions: List<Subscription>,
    onAdd: () -> Unit,
    onRefresh: (Subscription) -> Unit,
    onDelete: (Subscription) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScreenHeader(
                    title = "Подписки",
                    subtitle = "Автообновление, остаток трафика и срок действия",
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onAdd) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Добавить")
                }
            }
        }

        if (subscriptions.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    title = "Подписок нет",
                    description = "Вставьте ссылку подписки — клиент сам определит формат, " +
                        "загрузит серверы и покажет остаток трафика и дату окончания, если провайдер их сообщает.",
                    actionLabel = "Добавить подписку",
                    onAction = onAdd,
                )
            }
        }

        items(items = subscriptions, key = { it.id }, contentType = { "subscription" }) { subscription ->
            SubscriptionCard(
                subscription = subscription,
                onRefresh = { onRefresh(subscription) },
                onDelete = { onDelete(subscription) },
            )
        }
    }
}

@Composable
private fun SubscriptionCard(
    subscription: Subscription,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = subscription.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Обновлено ${Format.relativeTime(subscription.updatedAt)} · " +
                            "${subscription.profileCount} серверов",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Удалить")
                }
            }

            if (subscription.totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = { subscription.usageFraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        text = "Использовано ${Format.bytes(subscription.usedBytes)} " +
                            "из ${Format.bytes(subscription.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "Осталось ${Format.bytes(subscription.remainingBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                }
            } else {
                Text(
                    text = "Провайдер не передаёт лимит трафика",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when {
                subscription.expiresAt > 0L -> Text(
                    text = "Действует до ${Format.dateFromSeconds(subscription.expiresAt)} " +
                        "(${Format.daysLeft(subscription.daysLeft)})",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (subscription.isExpired) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )

                else -> Text(
                    text = "Срок действия не указан",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (subscription.lastError.isNotBlank()) {
                Text(
                    text = subscription.lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = onRefresh,
                    label = { Text("Обновить сейчас") },
                    leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                )
                Text(
                    text = "каждые ${subscription.updateIntervalHours} ч",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        }
    }
}
