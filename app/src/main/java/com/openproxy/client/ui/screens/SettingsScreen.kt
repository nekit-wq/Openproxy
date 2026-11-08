package com.openproxy.client.ui.screens

import android.os.Build
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openproxy.client.BuildConfig
import com.openproxy.client.core.model.AppSettings
import com.openproxy.client.core.model.DnsMode
import com.openproxy.client.core.model.PerAppMode
import com.openproxy.client.core.model.RoutingMode
import com.openproxy.client.core.model.ThemeMode
import com.openproxy.client.ui.components.ScreenHeader
import com.openproxy.client.ui.components.SectionCard
import com.openproxy.client.ui.components.SegmentedSelector
import com.openproxy.client.ui.components.SettingRow
import com.openproxy.client.ui.components.SwitchSetting

@Composable
fun SettingsScreen(
    settings: AppSettings,
    coreVersion: String,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onOpenPerApp: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            ScreenHeader(
                title = "Настройки",
                subtitle = "Всё можно менять на ходу — новые параметры применятся при следующем подключении",
            )
        }

        item(key = "appearance") {
            SectionCard(
                title = "Оформление",
                description = "Material 3 Expressive. Динамические цвета подстраиваются под обои.",
            ) {
                SegmentedSelector(
                    options = ThemeMode.entries,
                    label = {
                        when (it) {
                            ThemeMode.SYSTEM -> "Система"
                            ThemeMode.LIGHT -> "Светлая"
                            ThemeMode.DARK -> "Тёмная"
                        }
                    },
                    selected = settings.themeMode,
                    onSelect = { mode -> onChange { it.copy(themeMode = mode) } },
                )
                SwitchSetting(
                    title = "Динамический цвет",
                    description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        "Брать палитру из обоев (Material You, Android 12+)"
                    } else {
                        "Доступно на Android 12 и новее"
                    },
                    checked = settings.dynamicColor,
                    onCheckedChange = { enabled -> onChange { it.copy(dynamicColor = enabled) } },
                    enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                )
            }
        }

        item(key = "connection") {
            SectionCard(
                title = "Подключение",
                description = "Как трафик распределяется между туннелем и напрямую.",
            ) {
                Text(
                    text = "Режим маршрутизации",
                    style = MaterialTheme.typography.bodyLarge,
                )
                SegmentedSelector(
                    options = RoutingMode.entries,
                    label = {
                        when (it) {
                            RoutingMode.GLOBAL -> "Глобально"
                            RoutingMode.RULES -> "Правила"
                            RoutingMode.DIRECT -> "Напрямую"
                        }
                    },
                    selected = settings.routingMode,
                    onSelect = { mode -> onChange { it.copy(routingMode = mode) } },
                )
                Text(
                    text = when (settings.routingMode) {
                        RoutingMode.GLOBAL -> "Весь трафик идёт через сервер, включая локальную сеть."
                        RoutingMode.RULES -> "Локальная сеть остаётся напрямую, остальное — через сервер. " +
                            "Обычный режим для повседневного использования."
                        RoutingMode.DIRECT -> "Туннель поднят, но трафик не проксируется. " +
                            "Полезно для диагностики DNS и маршрутов."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SwitchSetting(
                    title = "Обходить локальную сеть",
                    description = "Принтеры, роутер, NAS и прочие адреса из частных диапазонов не пойдут через VPN.",
                    checked = settings.bypassLan,
                    onCheckedChange = { enabled -> onChange { it.copy(bypassLan = enabled) } },
                )
                SwitchSetting(
                    title = "IPv6 в туннеле",
                    description = "Добавляет IPv6-адрес и маршруты. Если мобильный оператор ломает IPv6 — оставьте выключенным.",
                    checked = settings.ipv6Enabled,
                    onCheckedChange = { enabled -> onChange { it.copy(ipv6Enabled = enabled) } },
                )
                MtuSetting(settings, onChange)
            }
        }

        item(key = "dns") {
            SectionCard(
                title = "DNS",
                description = "Через какой сервер клиент будет разрешать домены внутри туннеля.",
            ) {
                DnsMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChange { it.copy(dnsMode = mode) } }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = settings.dnsMode == mode,
                            onClick = { onChange { it.copy(dnsMode = mode) } },
                        )
                        Spacer(Modifier.width(4.dp))
                        Column(Modifier.weight(1f)) {
                            Text(mode.title, style = MaterialTheme.typography.bodyMedium)
                            if (mode == DnsMode.SYSTEM) {
                                Text(
                                    "DNS оператора. Быстро, но провайдер видит запросы.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                if (settings.dnsMode == DnsMode.CUSTOM) {
                    var customDns by remember(settings.customDns) {
                        mutableStateOf(settings.customDns)
                    }
                    OutlinedTextField(
                        value = customDns,
                        onValueChange = { customDns = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Свой DNS (IP или https://…)") },
                        shape = RoundedCornerShape(16.dp),
                    )
                    TextButton(onClick = { onChange { it.copy(customDns = customDns.trim()) } }) {
                        Text("Сохранить адрес")
                    }
                }

                SwitchSetting(
                    title = "DNS через туннель",
                    description = "Запросы уходят через сервер и не видны провайдеру. Выключите, если DNS долго отвечает.",
                    checked = settings.dnsThroughTunnel,
                    onCheckedChange = { enabled -> onChange { it.copy(dnsThroughTunnel = enabled) } },
                )
            }
        }

        item(key = "split") {
            SectionCard(
                title = "Раздельное туннелирование",
                description = "Выберите, какие приложения используют VPN, а какие ходят в интернет напрямую.",
            ) {
                SegmentedSelector(
                    options = PerAppMode.entries,
                    label = {
                        when (it) {
                            PerAppMode.ALL -> "Все"
                            PerAppMode.INCLUDE -> "Только выбранные"
                            PerAppMode.EXCLUDE -> "Кроме выбранных"
                        }
                    },
                    selected = settings.perAppMode,
                    onSelect = { mode -> onChange { it.copy(perAppMode = mode) } },
                )
                Text(
                    text = when (settings.perAppMode) {
                        PerAppMode.ALL -> "Весь трафик устройства идёт через туннель."
                        PerAppMode.INCLUDE -> "VPN используют только отмеченные приложения."
                        PerAppMode.EXCLUDE -> "VPN используют все приложения, кроме отмеченных."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = onOpenPerApp) { Text("Выбрать приложения") }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Выбрано: ${settings.perAppPackages.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item(key = "routing") {
            SectionCard(
                title = "Маршрутизация и правила",
                description = "Тонкая настройка: что блокировать и что пускать мимо VPN.",
            ) {
                SwitchSetting(
                    title = "Блокировать рекламу",
                    description = "Подключает список рекламных доменов и режет их до выхода в сеть. " +
                        "При первом включении список скачивается.",
                    checked = settings.blockAds,
                    onCheckedChange = { enabled -> onChange { it.copy(blockAds = enabled) } },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = onOpenRules) { Text("Свои правила") }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = if (settings.customRules.isBlank()) {
                            "не заданы"
                        } else {
                            "строк: ${settings.customRules.lines().count { it.isNotBlank() }}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "Формат: domain:example.com=direct, ip:10.0.0.0/8=direct, " +
                        "port:25=block. Цель — proxy, direct или block.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item(key = "advanced") {
            SectionCard(
                title = "Дополнительно",
                description = "Параметры, которые влияют на совместимость и диагностику.",
            ) {
                SwitchSetting(
                    title = "Определять протоколы приложений",
                    description = "Sniffing помогает правилам понимать домены даже в HTTPS-трафике без расшифровки.",
                    checked = settings.sniffEnabled,
                    onCheckedChange = { enabled -> onChange { it.copy(sniffEnabled = enabled) } },
                )
                Text("Уровень логов ядра", style = MaterialTheme.typography.bodyLarge)
                SegmentedSelector(
                    options = listOf("error", "info", "debug"),
                    label = {
                        when (it) {
                            "error" -> "Ошибки"
                            "debug" -> "Debug"
                            else -> "Обычный"
                        }
                    },
                    selected = settings.logLevel,
                    onSelect = { level -> onChange { it.copy(logLevel = level) } },
                )
                AssistChip(onClick = onOpenLogs, label = { Text("Открыть логи туннеля") })
            }
        }

        item(key = "subscriptions") {
            SectionCard(
                title = "Подписки",
                description = "Обновление списка серверов и сведений о трафике.",
            ) {
                SwitchSetting(
                    title = "Обновлять автоматически",
                    description = "При запуске приложения подписки старше своего интервала обновляются сами.",
                    checked = settings.autoUpdateSubscriptions,
                    onCheckedChange = { enabled -> onChange { it.copy(autoUpdateSubscriptions = enabled) } },
                )
            }
        }

        item(key = "about") {
            SectionCard(
                title = "О приложении",
                description = "OpenProxy ${BuildConfig.VERSION_NAME} · ядро sing-box $coreVersion",
            ) {
                Text(
                    text = "Поддерживаются VLESS (включая REALITY), VMess, Trojan, Shadowsocks, " +
                        "Hysteria 2, TUIC, WireGuard, SOCKS5 и SSH. Импортируются также AmneziaWG, " +
                        "OpenVPN, ShadowsocksR и Hysteria 1 — они сохраняются в списке, но встроенное " +
                        "ядро их не запускает; в карточке сервера указана причина.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Ключи и пароли хранятся локально в приватном хранилище приложения. " +
                        "Приложение не отправляет телеметрию.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MtuSetting(
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
) {
    var localMtu by remember(settings.mtu) { mutableFloatStateOf(settings.mtu.toFloat()) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingRow(
            title = "MTU туннеля",
            description = "Размер пакета внутри VPN. 9000 — быстро и надёжно в большинстве сетей; " +
                "1280 стоит попробовать при обрывах на мобильном интернете.",
            trailing = {
                Text(
                    text = "${localMtu.toInt()}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            },
        )
        Slider(
            value = localMtu,
            onValueChange = { localMtu = it },
            valueRange = 1280f..9000f,
            onValueChangeFinished = {
                onChange { it.copy(mtu = localMtu.toInt()) }
            },
        )
    }
}
