package com.openproxy.client.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openproxy.client.core.AppGraph
import com.openproxy.client.core.model.AppSettings
import com.openproxy.client.core.model.ServerProfile
import com.openproxy.client.core.model.Subscription
import com.openproxy.client.core.model.VpnStatus
import com.openproxy.client.core.net.LatencyTester
import com.openproxy.client.core.store.AppRepository
import com.openproxy.client.core.vpn.VpnController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class AppViewModel(private val repository: AppRepository) : ViewModel() {

    val profiles: StateFlow<List<ServerProfile>> = repository.profiles
    val subscriptions: StateFlow<List<Subscription>> = repository.subscriptions
    val settings: StateFlow<AppSettings> = repository.settings
    val vpnStatus: StateFlow<VpnStatus> = VpnController.status
    val logs: StateFlow<List<String>> = VpnController.logs
    val lastImport = repository.lastImport

    val selectedProfile: StateFlow<ServerProfile?> = combine(profiles, settings) { list, config ->
        list.firstOrNull { it.id == config.selectedProfileId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val latencyTester = LatencyTester()

    init {
        refreshStaleSubscriptions()
    }

    fun notify(message: String) {
        _message.value = message
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun selectProfile(id: String) {
        viewModelScope.launch { repository.selectProfile(id) }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repository.updateSettings(transform) }
    }

    fun importText(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _busy.value = "Импорт…"
            repository.importText(text)
                .onSuccess { count ->
                    _message.value = when {
                        count > 0 -> "Добавлено серверов: $count"
                        else -> "Новых серверов нет — они уже есть в списке"
                    }
                }
                .onFailure { error -> _message.value = error.message ?: "Не удалось импортировать" }
            _busy.value = null
        }
    }

    fun addSubscription(url: String) {
        if (url.isBlank()) return
        viewModelScope.launch {
            _busy.value = "Загрузка подписки…"
            repository.addSubscription(url.trim())
                .onSuccess { subscription ->
                    _message.value = "Подписка «${subscription.name}»: ${subscription.profileCount} серверов"
                }
                .onFailure { error -> _message.value = error.message ?: "Ошибка загрузки подписки" }
            _busy.value = null
        }
    }

    fun refreshSubscription(id: String) {
        viewModelScope.launch {
            _busy.value = "Обновление подписки…"
            repository.refreshSubscription(id)
                .onSuccess { _message.value = "Подписка обновлена: ${it.profileCount} серверов" }
                .onFailure { _message.value = it.message ?: "Не удалось обновить" }
            _busy.value = null
        }
    }

    fun deleteSubscription(id: String) {
        viewModelScope.launch {
            repository.deleteSubscription(id)
            _message.value = "Подписка удалена"
        }
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch {
            repository.deleteProfile(id)
            _message.value = "Сервер удалён"
        }
    }

    fun pingAll() {
        val targets = profiles.value
        if (targets.isEmpty()) return
        viewModelScope.launch {
            _busy.value = "Проверка задержки…"
            val results = ConcurrentHashMap<String, Int>()
            latencyTester.measureAll(targets) { profile, latency ->
                results[profile.id] = latency
            }
            repository.updateLatency(results)
            _busy.value = null
            val reachable = results.values.count { it >= 0 }
            _message.value = "Ответили $reachable из ${targets.size}"
        }
    }

    fun ping(profile: ServerProfile) {
        viewModelScope.launch {
            val latency = latencyTester.measure(profile)
            repository.updateLatency(mapOf(profile.id to latency))
        }
    }

    private fun refreshStaleSubscriptions() {
        viewModelScope.launch {
            if (!repository.settings.value.autoUpdateSubscriptions) return@launch
            val now = System.currentTimeMillis()
            repository.subscriptions.value
                .filter { subscription ->
                    subscription.autoUpdate &&
                        now - subscription.updatedAt > subscription.updateIntervalHours * 3_600_000L
                }
                .forEach { repository.refreshSubscription(it.id) }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { AppViewModel(AppGraph.repository) }
        }
    }
}
