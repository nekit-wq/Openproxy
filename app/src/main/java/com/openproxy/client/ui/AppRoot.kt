package com.openproxy.client.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openproxy.client.core.model.ConnectionState
import com.openproxy.client.ui.screens.HomeScreen
import com.openproxy.client.ui.screens.ImportSheet
import com.openproxy.client.ui.screens.PerAppSheet
import com.openproxy.client.ui.screens.ServersScreen
import com.openproxy.client.ui.screens.SettingsScreen
import com.openproxy.client.ui.screens.SubscriptionsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class TabItem(val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem("Главная", Icons.Filled.Home),
    TabItem("Серверы", Icons.Filled.List),
    TabItem("Подписки", Icons.Filled.Refresh),
    TabItem("Настройки", Icons.Filled.Settings),
)

@Composable
fun AppRoot(
    viewModel: AppViewModel,
    onConnect: (String?) -> Unit,
    onDisconnect: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val status by viewModel.vpnStatus.collectAsStateWithLifecycle()
    val selected by viewModel.selectedProfile.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showImport by rememberSaveable { mutableStateOf(false) }
    var showLogs by rememberSaveable { mutableStateOf(false) }
    var showPerApp by rememberSaveable { mutableStateOf(false) }
    var showRules by rememberSaveable { mutableStateOf(false) }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        val text = message
        if (text != null) {
            snackbar.showSnackbar(text)
            viewModel.consumeMessage()
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                viewModel.notify("Не удалось прочитать файл")
            } else {
                viewModel.importText(text)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == 1) {
                ExtendedFloatingActionButton(
                    onClick = { showImport = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Добавить") },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (tab) {
                0 -> HomeScreen(
                    status = status,
                    selected = selected,
                    profileCount = profiles.size,
                    onConnect = {
                        val id = selected?.id
                        if (id == null) {
                            tab = 1
                            viewModel.notify("Сначала выберите сервер")
                        } else {
                            onConnect(id)
                        }
                    },
                    onDisconnect = onDisconnect,
                    onPingAll = viewModel::pingAll,
                    onOpenServers = { tab = 1 },
                    onOpenLogs = { showLogs = true },
                )

                1 -> ServersScreen(
                    profiles = profiles,
                    subscriptions = subscriptions,
                    selectedId = selected?.id,
                    onSelect = { profile -> viewModel.selectProfile(profile.id) },
                    onConnect = { profile ->
                        viewModel.selectProfile(profile.id)
                        onConnect(profile.id)
                    },
                    onDelete = { profile -> viewModel.deleteProfile(profile.id) },
                    onPingAll = viewModel::pingAll,
                    onImport = { showImport = true },
                )

                2 -> SubscriptionsScreen(
                    subscriptions = subscriptions,
                    onAdd = { showImport = true },
                    onRefresh = { subscription -> viewModel.refreshSubscription(subscription.id) },
                    onDelete = { subscription -> viewModel.deleteSubscription(subscription.id) },
                )

                else -> SettingsScreen(
                    settings = settings,
                    coreVersion = status.coreVersion.ifBlank { "—" },
                    onChange = { transform -> viewModel.updateSettings(transform) },
                    onOpenPerApp = { showPerApp = true },
                    onOpenRules = { showRules = true },
                    onOpenLogs = { showLogs = true },
                )
            }

            if (busy != null) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                )
            }
        }
    }

    if (showImport) {
        ImportSheet(
            onDismiss = { showImport = false },
            onImport = { text -> viewModel.importText(text) },
            onPickFile = { filePicker.launch(arrayOf("*/*")) },
        )
    }

    if (showPerApp) {
        PerAppSheet(
            settings = settings,
            onDismiss = { showPerApp = false },
            onApply = { mode, packages ->
                viewModel.updateSettings {
                    it.copy(perAppMode = mode, perAppPackages = packages)
                }
            },
        )
    }

    if (showLogs) {
        LogsDialog(logs = logs, onDismiss = { showLogs = false })
    }

    if (showRules) {
        RulesDialog(
            initial = settings.customRules,
            onDismiss = { showRules = false },
            onSave = { value ->
                viewModel.updateSettings { it.copy(customRules = value) }
                viewModel.notify("Правила сохранены")
            },
        )
    }
}

@Composable
private fun LogsDialog(logs: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Логи туннеля") },
        text = {
            if (logs.isEmpty()) {
                Text(
                    text = "Пока пусто. Записи появятся после первого подключения.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    items(logs) { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}

@Composable
private fun RulesDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Свои правила") },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    shape = RoundedCornerShape(16.dp),
                    placeholder = { Text("domain:example.com=direct\nip:10.0.0.0/8=direct\nport:25=block") },
                )
                Text(
                    text = "Одна строка — одно правило. Условие: domain, domain_suffix, ip, port, process. " +
                        "Цель: proxy, direct или block. Неверные строки игнорируются.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Normal,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(value)
                    onDismiss()
                },
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
