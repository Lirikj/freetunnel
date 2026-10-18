package org.freetunnel.android

import com.adguard.trusttunnel.VpnClient

/** Build/destroy a native client without starting it, opening sockets or a TUN.
 * The pinned adapter has no public validation API; use its existing JNI factory.
 * Keep this contract tested when upgrading the adapter. */
object NativeConfigValidator {
    private val create by lazy { VpnClient::class.java.getDeclaredMethod("createNative", String::class.java).apply { isAccessible = true } }
    private val destroy by lazy { VpnClient::class.java.getDeclaredMethod("destroyNative", java.lang.Long.TYPE).apply { isAccessible = true } }

    fun validate(source: String) {
        // Never invoke the JNI factory on malformed TOML: older adapters return
        // a sentinel pointer on a parse failure instead of zero.
        RoutingConfig.validate(source)
        val owner = VpnClient(source, null)
        val pointer = create.invoke(owner, source) as Long
        require(pointer != 0L && pointer != 1L) { "Ядро VPN отвергло конфигурацию. Проверьте настройки сервера." }
        try { /* Constructing is validation; startNative is deliberately never called. */ }
        finally { destroy.invoke(owner, pointer) }
    }
}
