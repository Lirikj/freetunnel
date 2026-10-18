package org.freetunnel.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.edit
import androidx.core.app.NotificationCompat
import com.adguard.trusttunnel.CertificateVerificator
import com.adguard.trusttunnel.VpnClient
import com.adguard.trusttunnel.VpnClientListener
import com.adguard.trusttunnel.VpnServiceConfig
import com.adguard.trusttunnel.utils.NetworkUtils
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** All native resources belong to this instance and are used on its single worker. */
class FreeTunnelVpnService : VpnService() {
    companion object {
        const val ACTION_START = "org.freetunnel.android.START_VPN"
        const val ACTION_STOP = "org.freetunnel.android.STOP_VPN"
        const val ACTION_STATE = "org.freetunnel.android.VPN_STATE"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_STATE = "state"
        private const val CHANNEL = "freetunnel_vpn"
        private const val FAILURE_CHANNEL = "freetunnel_vpn_failure"
        private const val NOTIFICATION_ID = 47
        private const val FAILURE_ID = 48
        @Volatile private var currentState = 0
        @Volatile private var owner: FreeTunnelVpnService? = null

        @JvmStatic fun state(context: Context): Int = currentState
        @JvmStatic fun start(context: Context, source: String) {
            val intent = Intent(context, FreeTunnelVpnService::class.java).setAction(ACTION_START).putExtra(EXTRA_CONFIG, source)
            context.startForegroundService(intent)
        }
        @JvmStatic fun restart(context: Context, source: String) = start(context, source)
        @JvmStatic fun stop(context: Context) {
            context.startForegroundService(Intent(context, FreeTunnelVpnService::class.java).setAction(ACTION_STOP))
        }
    }

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "freetunnel-vpn") }
    private val prefs by lazy { getSharedPreferences("freetunnel", MODE_PRIVATE) }
    private var client: VpnClient? = null
    private var retainedTun: ParcelFileDescriptor? = null
    private var collector: NetworkUtils.Companion.NetworkCollector? = null
    private var generation = 0L
    private var establishedThisRequest = false
    @Volatile private var destroyed = false
    @Volatile private var latestStartId = 0

    override fun onCreate() {
        super.onCreate()
        owner = this
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "FreeTunnel VPN", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(FAILURE_CHANNEL, getString(R.string.text_vpn_disconnected), NotificationManager.IMPORTANCE_HIGH))
        startForeground(NOTIFICATION_ID, notification(getString(R.string.text_preparing_vpn)))
        // Migrate away from stale persistent state, including pre-upgrade installs.
        prefs.edit { remove("vpn_state"); remove("service_config_encrypted") }
    }

    private fun execute(action: () -> Unit) {
        try { worker.execute(action) } catch (_: RejectedExecutionException) { /* Instance already destroyed. */ }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        // A new start may arrive after stopForeground but before onDestroy.
        // onCreate is not called again in that window; acknowledge every FGS start.
        startForeground(NOTIFICATION_ID, notification(getString(R.string.text_connecting)))
        execute {
            if (destroyed || startId != latestStartId) return@execute
            if (intent?.action == ACTION_STOP) {
                close()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
                return@execute
            }
            val previous = client
            establishedThisRequest = false
            try {
                val source = intent?.getStringExtra(EXTRA_CONFIG) ?: SavedProfile.selected(prefs)
                val plan = RoutingConfig.prepare(this, source)
                replace(plan, startId)
            } catch (error: Throwable) {
                if (!destroyed && startId == latestStartId) {
                    if (previous != null && client === previous && !establishedThisRequest) {
                        logEvent("Настройки VPN не применены: ${error.javaClass.simpleName}")
                        getSystemService(NotificationManager::class.java).notify(FAILURE_ID,
                            NotificationCompat.Builder(this, FAILURE_CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_alert)
                                .setContentTitle(getString(R.string.text_vpn_settings_were_not_applied)).setContentText(getString(R.string.text_the_previous_connection_is_still_active))
                                .setContentIntent(openApp()).setAutoCancel(true).build())
                        return@execute
                    }
                    close()
                    logEvent("Ошибка запуска VPN: ${error.javaClass.simpleName}")
                    showFailure(getString(R.string.text_could_not_start_vpn))
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                }
            }
        }
        // Recreate an active service after process death using SavedProfile, while
        // transient connection state still starts disconnected in the new process.
        return if (intent?.action == ACTION_STOP) START_NOT_STICKY else START_STICKY
    }

    private fun replace(plan: RoutingConfig.Plan, startId: Int) {
        NativeConfigValidator.validate(plan.toml)
        val parsed = RoutingConfig.validate(plan.toml)
        val config = VpnServiceConfig.parseToml(plan.toml) ?: error("Неверный конфиг")
        val verifier = CertificateVerificator()
        val token = generation + 1
        val listener = object : VpnClientListener {
            override fun protectSocket(socket: Int) = protect(socket)
            override fun verifyCertificate(certificate: ByteArray?, rawChain: List<ByteArray?>?) = verifier.verifyCertificate(certificate, rawChain)
            override fun onConnectionInfo(info: String) = Unit
            override fun onStateChanged(state: Int) = execute {
                if (destroyed || token != generation) return@execute
                publishState(state)
                if (state == 0) {
                    close()
                    showFailure(getString(R.string.text_connection_to_the_server_was_lost))
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(latestStartId)
                } else {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,
                        notification(if (state == 2) getString(R.string.text_connected) else getString(R.string.text_recovering_connection), plan.warning))
                }
            }
        }
        var candidate: VpnClient? = null
        var descriptor: ParcelFileDescriptor? = null
        var guard: ParcelFileDescriptor? = null
        try {
            candidate = VpnClient(plan.toml, listener)
            if (destroyed || startId != latestStartId) return
            descriptor = createTun(config, parsed.getBoolean("endpoint.has_ipv6") ?: false, plan)
            establishedThisRequest = true
            guard = ParcelFileDescriptor.dup(descriptor.fileDescriptor)
            check(!destroyed) { "Запуск отменён" }
            // establish() replaces the VPN atomically. Only now release the previous
            // client and fd. The duplicate retains ownership throughout replacement.
            close(publish = false)
            generation = token
            client = candidate
            retainedTun = guard
            guard = null
            publishState(1)
            check(candidate.start(descriptor)) { "Ядро отвергло конфигурацию" }
            startNetworks(candidate)
            candidate = null
            getSystemService(NotificationManager::class.java).cancel(FAILURE_ID)
            logEvent("TrustTunnel: запуск соединения")
        } finally {
            runCatching { descriptor?.close() }
            runCatching { guard?.close() }
            candidate?.let { runCatching { it.stop() }; runCatching { it.close() } }
        }
    }

    private fun createTun(config: VpnServiceConfig, hasIpv6: Boolean, plan: RoutingConfig.Plan): ParcelFileDescriptor {
        val tun = config.listener.tun
        val ipv6 = hasIpv6 && tun.includedRoutes.any { ':' in it }
        require(tun.mtuSize in (if (ipv6) 1280L else 576L)..65535L) { "Неверный MTU" }
        val builder = Builder().setSession("FreeTunnel").setMtu(tun.mtuSize.toInt()).addAddress("172.20.2.13", 32)
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
        if (ipv6) builder.addAddress("fdfd:29::2", 128)
        // Never call allowFamily: an absent family must be blocked by Android.
        val dns = if (config.endpoint.dnsUpstreams.isEmpty()) listOf("46.243.231.30", "46.243.231.31") else listOf("198.18.53.53")
        dns.forEach(builder::addDnsServer)
        val included = tun.includedRoutes.filter { ipv6 || ':' !in it }
        val excluded = tun.excludedRoutes.filter { ipv6 || ':' !in it } + listOf("0.0.0.0/8", "224.0.0.0/3")
        val routes = VpnClient.excludeCidr(included, excluded) ?: error("Неверные маршруты")
        require(routes.isNotEmpty()) { "Нет маршрутов VPN" }
        routes.forEach {
            val pair = NetworkUtils.convertCidrToAddressPrefixPair(it) ?: error("Неверная подсеть")
            builder.addRoute(pair.first, pair.second)
        }
        val installed = plan.apps.filter { it != packageName }.filter {
            try { packageManager.getApplicationInfo(it, 0); true } catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }
        }
        if (plan.allowApps && installed.isNotEmpty()) {
            installed.forEach(builder::addAllowedApplication)
        } else {
            // If every saved app was uninstalled, the safe fallback is full VPN.
            if (!plan.allowApps) installed.forEach(builder::addDisallowedApplication)
            builder.addDisallowedApplication(packageName)
        }
        return builder.establish() ?: error("Android не создал интерфейс VPN")
    }

    private fun startNetworks(client: VpnClient) {
        val networks = NetworkUtils.Companion.NetworkCollector()
        networks.startNotifying(client)
        collector = networks
        getSystemService(ConnectivityManager::class.java).registerNetworkCallback(NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), networks)
    }

    private fun close(publish: Boolean = true) {
        generation++
        collector?.let {
            it.stopNotifying()
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        }
        collector = null
        val old = client; client = null
        old?.let { runCatching { it.stop() }; runCatching { it.close() } }
        runCatching { retainedTun?.close() }; retainedTun = null
        if (publish) publishState(0)
    }

    override fun onRevoke() {
        publishState(0)
        showFailure(getString(R.string.text_vpn_permission_was_revoked))
        val revokedId = latestStartId
        execute {
            if (revokedId == latestStartId) {
                close(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(revokedId)
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        publishState(0)
        if (owner === this) owner = null
        execute { close(publish = false) }
        worker.shutdown()
        super.onDestroy()
    }

    private fun publishState(state: Int) {
        if (owner !== this) return
        currentState = state
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName))
        QuickTileService.refresh(this)
    }

    private fun showFailure(reason: String) {
        logEvent(reason)
        getSystemService(NotificationManager::class.java).notify(FAILURE_ID,
            NotificationCompat.Builder(this, FAILURE_CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(getString(R.string.text_vpn_disconnected)).setContentText(reason)
                .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.vpn_failure_details, reason)))
                .setContentIntent(openApp()).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build())
    }

    private fun openApp() = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun notification(status: String, warning: Int? = null): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("FreeTunnel").setContentText(status)
        .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(status, warning?.let { getString(it) }).joinToString(". ")))
        .setContentIntent(openApp()).setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).build()

    private fun logEvent(message: String) {
        if (!prefs.getBoolean("logging", true)) return
        runCatching {
            val file = File(filesDir, "freetunnel.log")
            val old = if (file.exists()) file.readLines().takeLast(199) else emptyList()
            file.writeText((old + "${System.currentTimeMillis()} $message").joinToString("\n", postfix = "\n"))
        }
    }
}
