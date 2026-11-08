package com.openproxy.client.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openproxy.client.core.model.ConnectionState
import com.openproxy.client.core.model.ServerProfile
import com.openproxy.client.core.model.VpnStatus
import com.openproxy.client.core.util.Format
import com.openproxy.client.ui.components.InfoBanner
import com.openproxy.client.ui.components.LatencyBadge
import com.openproxy.client.ui.components.MetricTile
import com.openproxy.client.ui.components.ProtocolBadge
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    status: VpnStatus,
    selected: ServerProfile?,
    profileCount: Int,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onPingAll: () -> Unit,
    onOpenServers: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status.state, status.since) {
        while (status.state == ConnectionState.CONNECTED) {
            tick = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val session = if (status.since > 0L) Format.duration(tick - status.since) else "—"

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "hero") {
            ConnectionHero(
                status = status,
                selected = selected,
                session = session,
                onConnect = onConnect,
                onDisconnect = onDisconnect,
            )
        }

        item(key = "quick") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = onPingAll,
                    label = { Text("Пинговать") },
                    leadingIcon = {
                        Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
                    },
                )
                AssistChip(
                    onClick = onOpenServers,
                    label = { Text("Серверы ($profileCount)") },
                )
                AssistChip(
                    onClick = onOpenLogs,
                    label = { Text("Логи") },
                )
            }
        }

        item(key = "server") {
            SectionServerCard(
                selected = selected,
                onOpenServers = onOpenServers,
                onPingAll = onPingAll,
            )
        }

        item(key = "explain") {
            InfoBanner(
                "OpenProxy создаёт системный VPN-туннель: трафик приложений уходит через " +
                    "выбранный сервер. Пока туннель включён, в строке состояния виден значок VPN.",
            )
        }
    }
}

@Composable
private fun ConnectionHero(
    status: VpnStatus,
    selected: ServerProfile?,
    session: String,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val connected = status.state == ConnectionState.CONNECTED
    val active = status.isActive
    val container = when (status.state) {
        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primaryContainer
        ConnectionState.CONNECTING -> MaterialTheme.colorScheme.secondaryContainer
        ConnectionState.ERROR -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = when (status.state) {
        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.onPrimaryContainer
        ConnectionState.CONNECTING -> MaterialTheme.colorScheme.onSecondaryContainer
        ConnectionState.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val title = when (status.state) {
        ConnectionState.CONNECTED -> "Защита включена"
        ConnectionState.CONNECTING -> "Подключение…"
        ConnectionState.DISCONNECTING -> "Отключение…"
        ConnectionState.ERROR -> "Не удалось подключиться"
        ConnectionState.DISCONNECTED -> "VPN выключен"
    }
    val subtitle = when (status.state) {
        ConnectionState.CONNECTED -> "${status.profileName} · ${status.protocol}"
        ConnectionState.ERROR -> status.error ?: "Проверьте настройки сервера"
        ConnectionState.CONNECTING -> "Создаём туннель и проверяем связь"
        else -> selected?.let { "${it.title} · ${it.protocol.title}" } ?: "Сначала добавьте сервер"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            FilledIconButton(
                onClick = { if (active) onDisconnect() else if (selected != null) onConnect() },
                enabled = status.state != ConnectionState.CONNECTING &&
                    status.state != ConnectionState.DISCONNECTING &&
                    (selected != null || active),
                modifier = Modifier.size(104.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (active) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    contentColor = if (active) {
                        MaterialTheme.colorScheme.onError
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    },
                ),
            ) {
                Icon(
                    imageVector = if (active) Icons.Filled.Close else Icons.Filled.Lock,
                    contentDescription = if (active) "Отключить VPN" else "Подключить VPN",
                    modifier = Modifier.size(42.dp),
                )
            }
            Text(
                text = if (active) "Нажмите, чтобы отключиться" else "Нажмите, чтобы подключиться",
                style = MaterialTheme.typography.labelMedium,
            )
            if (connected || status.state == ConnectionState.CONNECTING) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MetricTile(
                        label = "получено",
                        value = Format.bytes(status.downlink),
                        modifier = Modifier.weight(1f),
                    )
                    MetricTile(
                        label = "отправлено",
                        value = Format.bytes(status.uplink),
                        modifier = Modifier.weight(1f),
                    )
                    MetricTile(
                        label = "сессия",
                        value = if (connected) session else "—",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionServerCard(
    selected: ServerProfile?,
    onOpenServers: () -> Unit,
    onPingAll: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Текущий сервер",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (selected == null) {
                Text(
                    text = "Сервер не выбран. Добавьте подписку или вставьте ссылку — это займёт пару секунд.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onOpenServers) { Text("Добавить сервер") }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = selected.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = selected.hostPort,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ProtocolBadge(selected.protocol)
                    Spacer(Modifier.size(8.dp))
                    LatencyBadge(selected)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onOpenServers) { Text("Сменить сервер") }
                    AssistChip(onClick = onPingAll, label = { Text("Проверить пинг") })
                }
                if (!selected.protocol.coreSupported) {
                    Text(
                        text = selected.protocol.coreNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
