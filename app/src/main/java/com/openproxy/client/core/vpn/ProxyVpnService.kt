package com.openproxy.client.core.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.openproxy.client.MainActivity
import com.openproxy.client.core.AppGraph
import com.openproxy.client.core.model.AppSettings
import com.openproxy.client.core.model.ConnectionState
import com.openproxy.client.core.model.PerAppMode
import com.openproxy.client.core.model.VpnStatus
import com.openproxy.client.core.singbox.ClashApiClient
import com.openproxy.client.core.singbox.SingBoxConfigBuilder
import com.openproxy.client.core.singbox.SingBoxRuntime
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.ServerSocket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Owns the tunnel. libbox creates and reloads the sing-box daemon; this service provides
 * the Android TUN file descriptor through [PlatformInterface.openTun], protects outgoing
 * sockets, and mirrors live status to the UI.
 */
class ProxyVpnService : VpnService(), PlatformInterface, CommandServerHandler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var commandServer: CommandServer? = null
    private var trafficJob: Job? = null
    private var clashPort = 0
    private var clashSecret = ""
    private var connectInProgress = false
    private var requestedProfileId: String? = null

    override fun onCreate() {
        super.onCreate()
        SingBoxRuntime.ensureInitialized(this)
        NotificationHelper.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                requestedProfileId = intent?.getStringExtra(EXTRA_PROFILE_ID)
                startForeground(
                    NotificationHelper.NOTIFICATION_ID,
                    NotificationHelper.build(this, VpnStatus(state = ConnectionState.CONNECTING)),
                )
                if (!connectInProgress && status.value.state != ConnectionState.CONNECTED) {
                    scope.launch { connect() }
                }
            }

            ACTION_DISCONNECT -> {
                scope.launch {
                    disconnect()
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------ connecting

    private suspend fun connect() {
        connectInProgress = true
        try {
            val repository = AppGraph.repository
            val settings = repository.settings.value
            val profileId = requestedProfileId ?: settings.selectedProfileId
            val profile = repository.profiles.value
                .firstOrNull { it.id == profileId }
                ?: error("Сначала выберите сервер")

            if (!profile.protocol.coreSupported) {
                error(profile.protocol.coreNote)
            }

            clashPort = freePort()
            clashSecret = UUID.randomUUID().toString().replace("-", "")
            val config = SingBoxConfigBuilder.build(profile, settings, clashPort, clashSecret)

            val server = Libbox.newCommandServer(this, this)
            server.start()
            commandServer = server

            val override = OverrideOptions().apply {
                autoRedirect = false
                val packages = settings.perAppPackages.toList()
                includePackage = LibboxStringIterator(
                    if (settings.perAppMode == PerAppMode.INCLUDE) packages else emptyList(),
                )
                excludePackage = LibboxStringIterator(
                    if (settings.perAppMode == PerAppMode.EXCLUDE) packages else emptyList(),
                )
            }
            server.startOrReloadService(config, override)

            updateStatus(
                VpnStatus(
                    state = ConnectionState.CONNECTED,
                    profileId = profile.id,
                    profileName = profile.title,
                    protocol = profile.protocol.title,
                    since = System.currentTimeMillis(),
                    coreVersion = SingBoxRuntime.version,
                ),
            )
            appendLog("Туннель поднят: ${profile.title} (${profile.protocol.title})")
            startTrafficPolling()
        } catch (t: Throwable) {
            Log.e(TAG, "connect failed", t)
            appendLog("Ошибка: ${t.message}")
            updateStatus(
                VpnStatus(
                    state = ConnectionState.ERROR,
                    error = t.message ?: "Не удалось запустить туннель",
                ),
            )
            runCatching { commandServer?.close() }
            commandServer = null
            NotificationManagerCompat.from(this)
                .notify(NotificationHelper.NOTIFICATION_ID, NotificationHelper.build(this, status.value))
            stopSelf()
        } finally {
            connectInProgress = false
        }
    }

    private fun startTrafficPolling() {
        trafficJob?.cancel()
        trafficJob = scope.launch {
            val client = ClashApiClient(clashPort, clashSecret)
            while (isActive) {
                try {
                    client.trafficFlow().collect { (up, down) ->
                        statusMutable.update { current ->
                            current.copy(uplink = up, downlink = down)
                        }
                    }
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                }
                delay(1500)
            }
        }
    }

    private suspend fun disconnect() {
        trafficJob?.cancel()
        trafficJob = null
        runCatching { commandServer?.closeService() }
        runCatching { commandServer?.close() }
        commandServer = null
        requestedProfileId = null
        appendLog("Туннель остановлен")
        updateStatus(VpnStatus(state = ConnectionState.DISCONNECTED))
        stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
    }

    override fun onRevoke() {
        scope.launch {
            disconnect()
            stopSelf()
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        trafficJob?.cancel()
        runCatching { commandServer?.close() }
        commandServer = null
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------- Android TUN fd

    override fun openTun(options: TunOptions): Int {
        val settings = AppGraph.repository.settings.value
        val builder = Builder()
            .setSession("OpenProxy")
            .setBlocking(true)
            .setMtu(options.mtu.takeIf { it in 1280..9000 } ?: settings.mtu)
            .setConfigureIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )

        var addresses = 0
        forEachPrefix(options.inet4Address) { prefix ->
            builder.addAddress(prefix.address(), prefix.prefix())
            addresses++
        }
        forEachPrefix(options.inet6Address) { prefix ->
            if (settings.ipv6Enabled) {
                builder.addAddress(prefix.address(), prefix.prefix())
                addresses++
            }
        }
        if (addresses == 0) builder.addAddress("172.19.0.1", 30)

        var routes = 0
        forEachPrefix(options.inet4RouteAddress) { prefix ->
            builder.addRoute(prefix.address(), prefix.prefix())
            routes++
        }
        forEachPrefix(options.inet6RouteAddress) { prefix ->
            if (settings.ipv6Enabled) {
                builder.addRoute(prefix.address(), prefix.prefix())
                routes++
            }
        }
        if (routes == 0) builder.addRoute("0.0.0.0", 0)

        var dnsCount = 0
        val dns = runCatching {
            val it = options.dnsServerAddress
            if (it != null && it.hasNext()) it.next() else null
        }.getOrNull()
        if (!dns.isNullOrBlank()) {
            runCatching { builder.addDnsServer(hostOnly(dns)) }
                .onSuccess { dnsCount++ }
        }
        if (dnsCount == 0) builder.addDnsServer("1.1.1.1")

        applySplitTunnel(builder, options, settings)

        val descriptor = builder.establish()
            ?: error("Не удалось создать TUN-интерфейс (VPN отклонён системой)")
        appendLog("TUN создан, MTU ${options.mtu}")
        // The descriptor is now owned by sing-box; closing it here would break the tunnel.
        return descriptor.detachFd()
    }

    private fun applySplitTunnel(
        builder: Builder,
        options: TunOptions,
        settings: AppSettings,
    ) {
        val include = forEachStringList(options.includePackage)
            .ifEmpty {
                if (settings.perAppMode == PerAppMode.INCLUDE) settings.perAppPackages.toList() else emptyList()
            }
        val exclude = forEachStringList(options.excludePackage)
            .ifEmpty {
                if (settings.perAppMode == PerAppMode.EXCLUDE) settings.perAppPackages.toList() else emptyList()
            }

        if (include.isNotEmpty()) {
            include.forEach { pkg ->
                runCatching { builder.addAllowedApplication(pkg) }
                    .onFailure { Log.w(TAG, "allowed app $pkg: ${it.message}") }
            }
            return
        }
        exclude.forEach { pkg ->
            runCatching { builder.addDisallowedApplication(pkg) }
                .onFailure { Log.w(TAG, "disallowed app $pkg: ${it.message}") }
        }
        // Never route our own process through its own TUN unless the user explicitly does it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            runCatching { builder.addDisallowedApplication(packageName) }
        }
    }

    // ------------------------------------------------------ PlatformInterface

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun cancelNotification(tag: String?, id: Int) = Unit

    override fun checkPlatformShell() = Unit

    override fun clearDNSCache() = Unit

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) = Unit

    override fun closeNeighborMonitor(listener: io.nekohasekai.libbox.NeighborUpdateListener?) = Unit

    override fun createAutoRedirect(key: ByteArray?, handler: io.nekohasekai.libbox.AutoRedirectHandler?): io.nekohasekai.libbox.AutoRedirectSession? = null

    override fun createBridge(options: io.nekohasekai.libbox.BridgeOptions?): io.nekohasekai.libbox.BridgeSession? = null

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String?,
        sourcePort: Int,
        destinationAddress: String?,
        destinationPort: Int,
    ): ConnectionOwner? = null

    override fun getInterfaces(): NetworkInterfaceIterator? = null

    override fun includeAllNetworks(): Boolean = false

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun lookupSFTPServer(): String = ""

    override fun lookupUser(name: String?): io.nekohasekai.libbox.PlatformUser? = null

    override fun openShellSession(
        user: io.nekohasekai.libbox.PlatformUser?,
        cmd: String?,
        args: io.nekohasekai.libbox.StringIterator?,
        workingDir: String?,
        cols: Int,
        rows: Int,
    ): io.nekohasekai.libbox.ShellSession? = null

    override fun readSystemSSHHostKey(): String = ""

    override fun readWIFIState(): WIFIState? = null

    override fun registerMyInterface(name: String?) = Unit

    override fun sendNotification(notification: Notification?) = Unit

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) = Unit

    override fun startNeighborMonitor(listener: io.nekohasekai.libbox.NeighborUpdateListener?) = Unit

    override fun systemCertificates(): StringIterator? = null

    override fun tailscaleHostname(): String = ""

    override fun underNetworkExtension(): Boolean = false

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun usePlatformAutoRedirect(): Boolean = false

    override fun usePlatformBridge(): Boolean = false

    override fun usePlatformShell(): Boolean = false

    override fun useProcFS(): Boolean = false

    // --------------------------------------------------- CommandServerHandler

    override fun connectSSHAgent(): Int = -1

    override fun getSystemProxyStatus(): SystemProxyStatus? = null

    override fun serviceReload() = Unit

    override fun serviceStop() {
        scope.launch {
            disconnect()
            stopSelf()
        }
    }

    override fun setSystemProxyEnabled(enabled: Boolean) = Unit

    override fun triggerNativeCrash() = Unit

    override fun writeDebugMessage(message: String?) {
        if (!message.isNullOrBlank()) appendLog(message)
    }

    // ---------------------------------------------------------------- helpers

    private fun updateStatus(next: VpnStatus) {
        statusMutable.value = next
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(NotificationHelper.NOTIFICATION_ID, NotificationHelper.build(this, next))
        }
    }

    private fun appendLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logsMutable.update { current ->
            (current + "$time  $message").takeLast(400)
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun hostOnly(value: String): String {
        val v = value.trim()
        return when {
            v.startsWith("[") -> v.substringAfter('[').substringBefore(']')
            v.count { it == ':' } > 1 -> v
            else -> v.substringBefore(':')
        }
    }

    private fun forEachString(iterator: StringIterator?, block: (String) -> Unit) {
        if (iterator == null) return
        while (iterator.hasNext()) {
            block(iterator.next())
        }
    }

    private fun forEachStringList(iterator: StringIterator?): List<String> {
        val result = mutableListOf<String>()
        forEachString(iterator) { result += it }
        return result
    }

    private fun forEachPrefix(
        iterator: io.nekohasekai.libbox.RoutePrefixIterator?,
        block: (io.nekohasekai.libbox.RoutePrefix) -> Unit,
    ) {
        if (iterator == null) return
        while (iterator.hasNext()) {
            block(iterator.next())
        }
    }

    private class LibboxStringIterator(private val items: List<String>) : StringIterator {
        private var index = 0

        override fun hasNext(): Boolean = index < items.size

        override fun len(): Int = items.size

        override fun next(): String = items[index++]
    }

    companion object {
        private const val TAG = "ProxyVpnService"
        const val ACTION_CONNECT = "com.openproxy.client.action.CONNECT"
        const val ACTION_DISCONNECT = "com.openproxy.client.action.DISCONNECT"
        const val EXTRA_PROFILE_ID = "com.openproxy.client.extra.PROFILE_ID"

        private val statusMutable = MutableStateFlow(VpnStatus())
        private val logsMutable = MutableStateFlow<List<String>>(emptyList())

        val status: StateFlow<VpnStatus> = statusMutable.asStateFlow()
        val logs: StateFlow<List<String>> = logsMutable.asStateFlow()

        fun connectIntent(context: Context, profileId: String? = null): Intent =
            Intent(context, ProxyVpnService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_PROFILE_ID, profileId)

        fun disconnectIntent(context: Context): Intent =
            Intent(context, ProxyVpnService::class.java).setAction(ACTION_DISCONNECT)
    }
}
