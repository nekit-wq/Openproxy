package com.openproxy.client.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.openproxy.client.core.model.AppSettings
import com.openproxy.client.core.model.PerAppMode
import com.openproxy.client.ui.components.SegmentedSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerAppSheet(
    settings: AppSettings,
    onDismiss: () -> Unit,
    onApply: (PerAppMode, Set<String>) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val apps by produceState<List<InstalledApp>>(emptyList()) {
        value = withContext(Dispatchers.IO) { loadApps(context) }
    }
    var query by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(settings.perAppMode) }
    var selected by remember { mutableStateOf(settings.perAppPackages) }
    val filtered = remember(apps, query) {
        val needle = query.trim().lowercase(Locale.getDefault())
        if (needle.isEmpty()) apps else apps.filter { it.label.lowercase(Locale.getDefault()).contains(needle) }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxHeight(0.92f)
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Приложения в VPN",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            SegmentedSelector(
                options = PerAppMode.entries,
                label = {
                    when (it) {
                        PerAppMode.ALL -> "Все"
                        PerAppMode.INCLUDE -> "Только выбранные"
                        PerAppMode.EXCLUDE -> "Кроме выбранных"
                    }
                },
                selected = mode,
                onSelect = { mode = it },
            )
            Text(
                text = "Выбрано приложений: ${selected.size}. " +
                    "Для режима «Все» отметки сохраняются, но не применяются.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                placeholder = { Text("Поиск приложения") },
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(items = filtered, key = { it.packageName }, contentType = { "app" }) { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (app.packageName in selected) {
                                    selected - app.packageName
                                } else {
                                    selected + app.packageName
                                }
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = app.packageName in selected,
                            onCheckedChange = {
                                selected = if (it) selected + app.packageName else selected - app.packageName
                            },
                        )
                        if (app.icon != null) {
                            Image(
                                bitmap = app.icon,
                                contentDescription = null,
                                modifier = Modifier.size(34.dp),
                            )
                        } else {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Filled.Info,
                                contentDescription = null,
                                modifier = Modifier.size(34.dp),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = app.label,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = app.packageName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text("Отмена") }
                Button(
                    onClick = {
                        onApply(mode, selected)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Применить") }
            }
        }
    }
}

private fun loadApps(context: Context): List<InstalledApp> {
    val manager = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    val activities = manager.queryIntentActivities(intent, 0)
    return activities
        .asSequence()
        .map { it.activityInfo.packageName }
        .distinct()
        .mapNotNull { packageName ->
            runCatching {
                val info = manager.getApplicationInfo(packageName, 0)
                InstalledApp(
                    packageName = packageName,
                    label = manager.getApplicationLabel(info).toString(),
                    icon = manager.getApplicationIcon(info)
                        .toBitmap(width = 72, height = 72)
                        .asImageBitmap(),
                )
            }.getOrNull()
        }
        .sortedBy { it.label.lowercase(Locale.getDefault()) }
        .toList()
}
