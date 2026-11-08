package com.openproxy.client.core.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.openproxy.client.core.model.AppSettings
import com.openproxy.client.core.model.ServerProfile
import com.openproxy.client.core.model.Subscription
import com.openproxy.client.core.net.SubscriptionFetcher
import com.openproxy.client.core.parse.ImportDetector
import com.openproxy.client.core.parse.ImportSource
import com.openproxy.client.core.parse.ParseBatch
import com.openproxy.client.core.parse.SubscriptionParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.openProxyStore: DataStore<Preferences> by preferencesDataStore(name = "openproxy")

/**
 * Single source of truth. Everything is persisted as JSON in Preferences DataStore:
 * small, transactional, and it survives schema additions because unknown keys are ignored.
 */
class AppRepository(
    context: Context,
    private val fetcher: SubscriptionFetcher = SubscriptionFetcher(),
) {
    private val dataStore = context.applicationContext.openProxyStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }
    private val writeMutex = Mutex()

    private object Keys {
        val profiles = stringPreferencesKey("profiles")
        val subscriptions = stringPreferencesKey("subscriptions")
        val settings = stringPreferencesKey("settings")
    }

    val profiles: StateFlow<List<ServerProfile>> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { decodeProfiles(it) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val subscriptions: StateFlow<List<Subscription>> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { decodeSubscriptions(it) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val settings: StateFlow<AppSettings> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { decodeSettings(it) }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    /** Last import result, surfaced to the UI as a one-shot message. */
    private val _lastImport = MutableStateFlow<ParseBatch?>(null)
    val lastImport: StateFlow<ParseBatch?> = _lastImport

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        writeMutex.withLock {
            dataStore.edit { prefs ->
                prefs[Keys.settings] = json.encodeToString(transform(decodeSettings(prefs)))
            }
        }
    }

    suspend fun selectProfile(id: String?) = updateSettings { it.copy(selectedProfileId = id) }

    suspend fun addProfiles(incoming: List<ServerProfile>): Int = withContext(Dispatchers.IO) {
        if (incoming.isEmpty()) return@withContext 0
        var added = 0
        writeMutex.withLock {
            dataStore.edit { prefs ->
                val existing = decodeProfiles(prefs)
                val seen = existing.mapTo(mutableSetOf()) { it.signature }
                val fresh = incoming.mapNotNull { profile ->
                    if (seen.add(profile.signature)) profile else null
                }
                added = fresh.size
                if (fresh.isNotEmpty()) {
                    prefs[Keys.profiles] = json.encodeToString(existing + fresh)
                    val current = decodeSettings(prefs)
                    if (current.selectedProfileId == null) {
                        prefs[Keys.settings] = json.encodeToString(
                            current.copy(selectedProfileId = fresh.first().id),
                        )
                    }
                }
            }
        }
        added
    }

    suspend fun deleteProfile(id: String) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            dataStore.edit { prefs ->
                prefs[Keys.profiles] = json.encodeToString(decodeProfiles(prefs).filterNot { it.id == id })
                val current = decodeSettings(prefs)
                if (current.selectedProfileId == id) {
                    val next = decodeProfiles(prefs).firstOrNull()?.id
                    prefs[Keys.settings] = json.encodeToString(current.copy(selectedProfileId = next))
                }
            }
        }
    }

    suspend fun updateLatency(results: Map<String, Int>) = withContext(Dispatchers.IO) {
        if (results.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        writeMutex.withLock {
            dataStore.edit { prefs ->
                val updated = decodeProfiles(prefs).map { profile ->
                    results[profile.id]?.let {
                        profile.copy(latencyMs = it, latencyCheckedAt = now)
                    } ?: profile
                }
                prefs[Keys.profiles] = json.encodeToString(updated)
            }
        }
    }

    private suspend fun obtainDeviceId(): String {
        val currentSettings = settings.value
        if (currentSettings.deviceId.isNotBlank()) {
            return currentSettings.deviceId
        }
        val generated = UUID.randomUUID().toString()
        updateSettings { it.copy(deviceId = generated) }
        return generated
    }

    suspend fun addSubscription(url: String): Result<Subscription> = withContext(Dispatchers.IO) {
        try {
            val deviceId = obtainDeviceId()
            val currentSettings = settings.value
            val response = fetcher.fetch(url, currentSettings.subscriptionUserAgent, deviceId)
            val meta = SubscriptionParser.parseMeta(response.headers)
            val batch = SubscriptionParser.parseBody(response.body)
            if (batch.isEmpty) {
                error(batch.errors.firstOrNull() ?: "В подписке не найдено ни одного сервера")
            }
            val now = System.currentTimeMillis()
            val id = UUID.randomUUID().toString()
            val newProfiles = batch.profiles.mapIndexed { index, profile ->
                profile.copy(
                    id = stableId(id, profile, index),
                    subscriptionId = id,
                    updatedAt = now,
                )
            }
            val subscription = Subscription(
                id = id,
                name = meta.name.ifBlank { url.substringAfter("://").substringBefore('/') },
                url = url,
                updatedAt = now,
                updateIntervalHours = meta.updateIntervalHours.coerceIn(1, 168),
                uploadBytes = meta.uploadBytes,
                downloadBytes = meta.downloadBytes,
                totalBytes = meta.totalBytes,
                expiresAt = meta.expiresAtSeconds,
                webPageUrl = meta.webPageUrl,
                profileCount = newProfiles.size,
            )
            writeMutex.withLock {
                dataStore.edit { prefs ->
                    prefs[Keys.subscriptions] =
                        json.encodeToString(decodeSubscriptions(prefs) + subscription)
                    prefs[Keys.profiles] =
                        json.encodeToString(decodeProfiles(prefs) + newProfiles)
                    val current = decodeSettings(prefs)
                    if (current.selectedProfileId == null) {
                        prefs[Keys.settings] = json.encodeToString(
                            current.copy(selectedProfileId = newProfiles.firstOrNull()?.id),
                        )
                    }
                }
            }
            Result.success(subscription)
        } catch (t: Throwable) {
            Result.failure(IllegalStateException(t.message ?: "Не удалось загрузить подписку", t))
        }
    }

    suspend fun refreshSubscription(id: String): Result<Subscription> = withContext(Dispatchers.IO) {
        val current = subscriptions.value.firstOrNull { it.id == id }
            ?: return@withContext Result.failure(IllegalArgumentException("Подписка не найдена"))
        try {
            val deviceId = obtainDeviceId()
            val currentSettings = settings.value
            val response = fetcher.fetch(current.url, currentSettings.subscriptionUserAgent, deviceId)
            val meta = SubscriptionParser.parseMeta(response.headers)
            val batch = SubscriptionParser.parseBody(response.body)
            if (batch.isEmpty) {
                error(batch.errors.firstOrNull() ?: "В подписке не найдено ни одного сервера")
            }
            val now = System.currentTimeMillis()
            val previous = profiles.value
                .filter { it.subscriptionId == id }
                .associateBy { it.signature }
            val refreshed = batch.profiles.mapIndexed { index, profile ->
                val prepared = profile.copy(
                    id = stableId(id, profile, index),
                    subscriptionId = id,
                    updatedAt = now,
                )
                prepared.copy(latencyMs = previous[prepared.signature]?.latencyMs ?: -1)
            }
            var result: Subscription? = null
            writeMutex.withLock {
                dataStore.edit { prefs ->
                    val updatedSubscription = current.copy(
                        updatedAt = now,
                        uploadBytes = meta.uploadBytes,
                        downloadBytes = meta.downloadBytes,
                        totalBytes = meta.totalBytes,
                        expiresAt = meta.expiresAtSeconds,
                        updateIntervalHours = meta.updateIntervalHours.coerceIn(1, 168),
                        lastError = "",
                        profileCount = refreshed.size,
                        webPageUrl = meta.webPageUrl.ifBlank { current.webPageUrl },
                        name = meta.name.ifBlank { current.name },
                    )
                    result = updatedSubscription
                    prefs[Keys.subscriptions] = json.encodeToString(
                        decodeSubscriptions(prefs).map {
                            if (it.id == id) updatedSubscription else it
                        },
                    )
                    prefs[Keys.profiles] = json.encodeToString(
                        decodeProfiles(prefs).filterNot { it.subscriptionId == id } + refreshed,
                    )
                }
            }
            Result.success(checkNotNull(result))
        } catch (t: Throwable) {
            val message = t.message ?: "Ошибка обновления подписки"
            writeMutex.withLock {
                dataStore.edit { prefs ->
                    prefs[Keys.subscriptions] = json.encodeToString(
                        decodeSubscriptions(prefs).map {
                            if (it.id == id) it.copy(lastError = message) else it
                        },
                    )
                }
            }
            Result.failure(IllegalStateException(message, t))
        }
    }

    suspend fun deleteSubscription(id: String) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            dataStore.edit { prefs ->
                prefs[Keys.subscriptions] =
                    json.encodeToString(decodeSubscriptions(prefs).filterNot { it.id == id })
                prefs[Keys.profiles] = json.encodeToString(
                    decodeProfiles(prefs).filterNot { it.subscriptionId == id },
                )
                val current = decodeSettings(prefs)
                if (current.selectedProfileId != null &&
                    decodeProfiles(prefs).none { it.id == current.selectedProfileId }
                ) {
                    prefs[Keys.settings] = json.encodeToString(
                        current.copy(selectedProfileId = decodeProfiles(prefs).firstOrNull()?.id),
                    )
                }
            }
        }
    }

    /** One entry point for pasted text: subscription URL, link list, or config file. */
    suspend fun importText(text: String): Result<Int> = withContext(Dispatchers.IO) {
        when (val source = ImportDetector.detect(text)) {
            is ImportSource.Subscription -> {
                addSubscription(source.url).map { subscription -> subscription.profileCount }
            }

            is ImportSource.Links -> {
                val batch = SubscriptionParser.parseBody(source.text)
                _lastImport.value = batch
                if (batch.isEmpty) {
                    Result.failure(
                        IllegalStateException(batch.errors.firstOrNull() ?: "Не удалось распознать конфигурацию"),
                    )
                } else {
                    Result.success(addProfiles(batch.profiles))
                }
            }

            null -> Result.failure(IllegalArgumentException("Пустой ввод"))
        }
    }

    fun clearImportState() {
        _lastImport.value = null
    }

    // ------------------------------------------------------------ decoding

    private fun decodeProfiles(prefs: Preferences): List<ServerProfile> =
        prefs[Keys.profiles]?.let {
            runCatching { json.decodeFromString<List<ServerProfile>>(it) }.getOrNull()
        } ?: emptyList()

    private fun decodeSubscriptions(prefs: Preferences): List<Subscription> =
        prefs[Keys.subscriptions]?.let {
            runCatching { json.decodeFromString<List<Subscription>>(it) }.getOrNull()
        } ?: emptyList()

    private fun decodeSettings(prefs: Preferences): AppSettings =
        prefs[Keys.settings]?.let {
            runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull()
        } ?: AppSettings()

    private fun stableId(subscriptionId: String, profile: ServerProfile, index: Int): String {
        val key = "$subscriptionId|${profile.protocol}|${profile.address}|${profile.port}|" +
            "${profile.uuid}|${profile.password}|$index"
        return UUID.nameUUIDFromBytes(key.toByteArray()).toString()
    }
}

val ServerProfile.signature: String
    get() = "${protocol.name}|$address|$port|$uuid|$password|$method|$network|$rawLink"
