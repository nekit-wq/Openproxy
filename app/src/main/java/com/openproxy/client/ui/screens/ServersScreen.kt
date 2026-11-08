package com.openproxy.client.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openproxy.client.core.model.ServerProfile
import com.openproxy.client.core.model.Subscription
import com.openproxy.client.ui.components.EmptyState
import com.openproxy.client.ui.components.LatencyBadge
import com.openproxy.client.ui.components.ProtocolBadge
import com.openproxy.client.ui.components.ScreenHeader

@Composable
fun ServersScreen(
    profiles: List<ServerProfile>,
    subscriptions: List<Subscription>,
    selectedId: String?,
    onSelect: (ServerProfile) -> Unit,
    onConnect: (ServerProfile) -> Unit,
    onDelete: (ServerProfile) -> Unit,
    onPingAll: () -> Unit,
    onImport: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var subscriptionFilter by rememberSaveable { mutableStateOf<String?>(null) }

    val filtered = remember(profiles, query, subscriptionFilter) {
        val needle = query.trim().lowercase()
        profiles.filter { profile ->
            val matchesQuery = needle.isEmpty() ||
                profile.title.lowercase().contains(needle) ||
                profile.address.lowercase().contains(needle) ||
                profile.protocol.title.lowercase().contains(needle)
            val matchesSubscription = subscriptionFilter == null ||
                profile.subscriptionId == subscriptionFilter
            matchesQuery && matchesSubscription
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScreenHeader(
                    title = "Серверы",
                    subtitle = "${profiles.size} профилей · выберите и подключитесь",
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(onClick = onPingAll) { Text("Пинг") }
            }
        }

        item(key = "search") {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Поиск по имени, адресу, протоколу") },
            )
        }

        if (subscriptions.isNotEmpty()) {
            item(key = "filters") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { subscriptionFilter = null },
                        label = { Text("Все") },
                    )
                    subscriptions.take(3).forEach { subscription ->
                        AssistChip(
                            onClick = {
                                subscriptionFilter = if (subscriptionFilter == subscription.id) null else subscription.id
                            },
                            label = {
                                Text(
                                    text = subscription.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    title = if (profiles.isEmpty()) "Пока нет серверов" else "Ничего не найдено",
                    description = if (profiles.isEmpty()) {
                        "Вставьте ссылку vless/vmess/trojan/ss или добавьте подписку — автоопределение сделает всё само."
                    } else {
                        "Попробуйте изменить запрос или сбросить фильтр подписки."
                    },
                    actionLabel = if (profiles.isEmpty()) "Импортировать" else null,
                    onAction = if (profiles.isEmpty()) onImport else null,
                )
            }
        }

        items(
            items = filtered,
            key = { it.id },
            contentType = { "server" },
        ) { profile ->
            ServerRow(
                profile = profile,
                selected = profile.id == selectedId,
                onSelect = { onSelect(profile) },
                onConnect = { onConnect(profile) },
                onDelete = { onDelete(profile) },
            )
        }
    }
}

@Composable
private fun ServerRow(
    profile: ServerProfile,
    selected: Boolean,
    onSelect: () -> Unit,
    onConnect: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(22.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(38.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(Icons.Filled.Check, contentDescription = "Выбран")
                } else {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = profile.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProtocolBadge(profile.protocol)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = profile.hostPort,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!profile.protocol.coreSupported) {
                    Text(
                        text = "Импорт без подключения",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            LatencyBadge(profile)
            IconButton(onClick = onConnect) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Подключиться")
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Ещё")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Удалить") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}
