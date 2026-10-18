package org.freetunnel.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.net.VpnService
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.adguard.trusttunnel.VpnServiceConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

class VpnDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val prefs get() = context.getSharedPreferences("freetunnel", Context.MODE_PRIVATE)
    private var oldPreferences: Map<String, *> = emptyMap<String, Any>()
    private val source = """
        vpn_mode = "general"
        killswitch_enabled = true
        exclusions = []
        [endpoint] # import comment
        hostname = "vpn.invalid"
        addresses = ["192.0.2.1:443"]
        username = "test"
        password = "test"
        upstream_protocol = "http2"
        has_ipv6 = false
        [listener.tun] # multiline import
        included_routes = [
          "0.0.0.0/0", "::/0",
        ]
        excluded_routes = []
        mtu_size = 1350
    """.trimIndent()
    private fun network() = context.getSystemService(ConnectivityManager::class.java).let { cm ->
        cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }
    private fun eventually(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Condition did not become true", condition())
    }
    @Before fun setup() {
        context.getSystemService(android.app.NotificationManager::class.java).cancel(48)
        oldPreferences = prefs.all
        prefs.edit().clear().putBoolean("logging", false).commit()
    }
    @After fun cleanup() {
        context.stopService(Intent(context, FreeTunnelVpnService::class.java))
        eventually { network() == null && FreeTunnelVpnService.state(context) == 0 }
        val editor = prefs.edit().clear()
        oldPreferences.forEach { (key, value) -> when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> { @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>) }
        } }
        editor.commit()
    }
    @Test fun consentEntryIsPrivate() {
        assertFalse(context.packageManager.getActivityInfo(ComponentName(context, VpnPermissionActivity::class.java), 0).exported)
    }
    @Test fun persistedConnectedValueCannotSpoofState() {
        prefs.edit().putInt("vpn_state", 2).commit()
        assertEquals(0, FreeTunnelVpnService.state(context))
    }
    @Test fun adapterAndNativeFactoryAcceptSerializedTablesAndRejectBadCoreValues() {
        val plan = RoutingConfig.prepare(source, false, false, emptyList(), emptyList(), true)
        assertNotNull(VpnServiceConfig.parseToml(plan.toml))
        NativeConfigValidator.validate(plan.toml)
        val invalid = source.replace("has_ipv6 = false", "has_ipv6 = false\nclient_random = \"abcd/\"")
        assertThrows(IllegalArgumentException::class.java) { NativeConfigValidator.validate(invalid) }
        assertNull(network())
    }
    @Test fun corruptedCiphertextIsNeverReplaced() {
        val isolated = context.getSharedPreferences("damaged-config-test", 0)
        val ciphertext = "AAAA"
        isolated.edit().clear().putString("configs_encrypted", ciphertext).commit()
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val before = keys.aliases().toList().toSet()
        assertThrows(IllegalStateException::class.java) { ConfigStore.read(isolated) }
        assertFalse(ConfigStore.write(isolated, "[]"))
        assertEquals(ciphertext, isolated.getString("configs_encrypted", null))
        keys.load(null)
        assertEquals(before, keys.aliases().toList().toSet())
        isolated.edit().clear().commit()
    }
    @Test fun encryptedConfigsRoundTripAndPlaintextMigrationRemainsCompatible() {
        val isolated = context.getSharedPreferences("cipher-config-test", 0)
        val json = """[{"name":"Private","toml":"password = very-private"}]"""
        isolated.edit().clear().putString("configs", json).commit()
        assertEquals(json, ConfigStore.read(isolated))
        assertTrue(ConfigStore.write(isolated, json))
        assertFalse(isolated.contains("configs"))
        assertFalse(isolated.getString("configs_encrypted", "")!!.contains("very-private"))
        assertEquals(json, ConfigStore.read(isolated))
        isolated.edit().clear().commit()
    }
    @Test fun staleAppRulesFallBackToFullVpnWithWarning() {
        prefs.edit().putBoolean("split_enabled", true).putBoolean("through", true)
            .putStringSet("apps", setOf("not.installed.package", context.packageName)).commit()
        val plan = RoutingConfig.prepare(context, source)
        assertTrue(plan.apps.isEmpty()); assertFalse(plan.allowApps); assertNotNull(plan.warning)
    }
    @Test fun replacesTunWithoutDisconnectedStateAndBlocksUnsupportedIpv6() {
        assertNull("Grant ACTIVATE_VPN before running device tests", VpnService.prepare(context))
        val sink = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val sockets = ConcurrentLinkedQueue<Socket>()
        val accepting = thread(name = "test-tls-sink", isDaemon = true) {
            runCatching { while (!sink.isClosed) sockets.add(sink.accept()) }
        }
        val config = source.replace("192.0.2.1:443", "127.0.0.1:${sink.localPort}")
        val states = ConcurrentLinkedQueue<Int>()
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) { states.add(FreeTunnelVpnService.state(context)) }
        }
        androidx.core.content.ContextCompat.registerReceiver(context, receiver, android.content.IntentFilter(FreeTunnelVpnService.ACTION_STATE),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            FreeTunnelVpnService.start(context, config)
            eventually { network() != null && FreeTunnelVpnService.state(context) != 0 }
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val properties = cm.getLinkProperties(network()!!)!!
            assertFalse(properties.linkAddresses.any { it.address is java.net.Inet6Address })
            assertFalse(properties.routes.any { it.destination.address is java.net.Inet6Address && it.type == RouteInfo.RTN_UNICAST })
            assertTrue(cm.getNetworkCapabilities(network()!!)!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            instrumentation.startActivitySync(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                .putExtra("tile_connect", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
            assertNotNull("Exported launcher must not stop VPN", network())
            states.clear()
            repeat(4) { FreeTunnelVpnService.restart(context, config.replace("1350", (1400 + it).toString())) }
            eventually { network()?.let { cm.getLinkProperties(it)?.mtu == 1403 } == true }
            assertFalse("Replacement must never publish disconnected", states.contains(0))
            FreeTunnelVpnService.restart(context, config.replace("has_ipv6 = false", "has_ipv6 = false\nclient_random = \"abcd/\""))
            SystemClock.sleep(750)
            assertNotNull("Rejected replacement must retain old TUN", network())
            assertEquals(1403, cm.getLinkProperties(network()!!)!!.mtu)
            FreeTunnelVpnService.stop(context)
            eventually { network() == null && FreeTunnelVpnService.state(context) == 0 }
        } finally {
            context.unregisterReceiver(receiver)
            context.stopService(Intent(context, FreeTunnelVpnService::class.java))
            sink.close(); accepting.join(500); sockets.forEach { it.close() }
        }
    }
    @Test fun androidCanRecreateServiceFromSavedSelectedProfile() {
        assertNull(VpnService.prepare(context))
        val sink = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val sockets = ConcurrentLinkedQueue<Socket>()
        val accepting = thread(isDaemon = true) { runCatching { while (!sink.isClosed) sockets.add(sink.accept()) } }
        try {
            val config = source.replace("192.0.2.1:443", "127.0.0.1:${sink.localPort}").replace("1350", "1390")
            val json = org.json.JSONArray().put(org.json.JSONObject().put("name", "Recovery test").put("toml", config)).toString()
            assertTrue(ConfigStore.write(prefs, json))
            // Android's sticky/Always-on recovery has no config or action extras.
            context.startForegroundService(Intent(context, FreeTunnelVpnService::class.java))
            val cm = context.getSystemService(ConnectivityManager::class.java)
            eventually { network()?.let { cm.getLinkProperties(it)?.mtu == 1390 } == true }
            assertNotEquals(0, FreeTunnelVpnService.state(context))
        } finally {
            context.stopService(Intent(context, FreeTunnelVpnService::class.java))
            sink.close(); accepting.join(500); sockets.forEach { it.close() }
        }
    }
    @Test fun malformedConfigCannotLeaveATunOrConnectedState() {
        FreeTunnelVpnService.start(context, source + "\ninvalid TOML")
        eventually { context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any { it.id == 48 } }
        eventually { network() == null && FreeTunnelVpnService.state(context) == 0 }
    }
    @Test fun rejectedNativeConfigCannotLeaveATunOrConnectedState() {
        FreeTunnelVpnService.start(context, source.replace("has_ipv6 = false", "has_ipv6 = false\nclient_random = \"abcd/\""))
        eventually { context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any { it.id == 48 } }
        eventually { network() == null && FreeTunnelVpnService.state(context) == 0 }
    }
}
