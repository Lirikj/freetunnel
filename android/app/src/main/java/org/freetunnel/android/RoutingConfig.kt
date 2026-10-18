package org.freetunnel.android

import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlTable

object RoutingConfig {
    private fun quote(text: String): String = "\"" + buildString {
        text.forEach { c -> append(when (c) {
            '\\' -> "\\\\"; '"' -> "\\\""; '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"
            else -> if (c.code < 32 || c.code == 127) "\\u%04x".format(c.code) else c.toString()
        }) }
    } + "\""
    private fun render(item: Any): String = when (item) {
        is String -> quote(item)
        is TomlTable -> item.keySet().joinToString(", ", "{ ", " }") { "${quote(it)} = ${render(item.get(listOf(it))!!)}" }
        is TomlArray -> (0 until item.size()).joinToString(", ", "[", "]") { render(item.get(it)) }
        is List<*> -> item.joinToString(", ", "[", "]") { render(it!!) }
        is Double -> when { item.isNaN() -> "nan"; item == Double.POSITIVE_INFINITY -> "inf"; item == Double.NEGATIVE_INFINITY -> "-inf"; else -> item.toString() }
        is java.time.LocalTime -> item.format(java.time.format.DateTimeFormatter.ISO_LOCAL_TIME)
        is java.time.LocalDateTime -> item.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        is java.time.OffsetDateTime -> item.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        else -> item.toString()
    }
    private fun parse(source: String): TomlTable {
        require(source.length <= 1_048_576) { "Конфигурация слишком большая (максимум 1 МБ)" }
        val parsed = Toml.parse(source)
        require(!parsed.hasErrors()) { "Ошибка TOML: ${parsed.errors().firstOrNull()?.position()}" }
        require(parsed.getTable("endpoint") != null) { "В конфигурации отсутствует раздел [endpoint]" }
        return parsed
    }
    @JvmStatic fun validate(source: String): TomlTable {
        val parsed = parse(source)
        val endpoint = parsed.getTable("endpoint")!!
        require(!endpoint.getString("hostname").isNullOrBlank()) { "Укажите endpoint.hostname" }
        require(endpoint.getString("username") != null && endpoint.getString("password") != null) { "Укажите имя пользователя и пароль сервера" }
        val addresses = endpoint.getArray("addresses")
        require(addresses != null && addresses.size() > 0 && (0 until addresses.size()).all {
            val address = addresses.getString(it)
            val port = address.substringAfterLast(':', "").toIntOrNull()
            port != null && port in 1..65535 && address.substringBeforeLast(':').isNotBlank()
        }) { "Укажите addresses в формате адрес:порт" }
        require(endpoint.getString("upstream_protocol")?.let { it in listOf("http2", "http3") } != false) { "Укажите upstream_protocol: http2 или http3" }
        require(parsed.getString("vpn_mode")?.let { it in listOf("general", "selective") } != false) { "Неизвестный vpn_mode" }
        parsed.getArray("exclusions")?.let { a -> (0 until a.size()).forEach { a.getString(it) } }
        return parsed
    }

    data class Plan(val toml: String, val apps: List<String>, val allowApps: Boolean, val warning: Int?)

    @JvmStatic fun prepare(context: android.content.Context, source: String): Plan {
        val prefs = context.getSharedPreferences("freetunnel", android.content.Context.MODE_PRIVATE)
        val apps = prefs.getStringSet("apps", emptySet()).orEmpty().filter { it != context.packageName }.filter {
            try { context.packageManager.getApplicationInfo(it, 0); true }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }
        }
        return prepare(source, prefs.getBoolean("split_enabled", false), prefs.getBoolean("through", false),
            prefs.getStringSet("domains", emptySet()).orEmpty().toList(), apps, prefs.getBoolean("killswitch", true))
    }

    @JvmStatic fun prepare(source: String, enabled: Boolean, through: Boolean,
                           domains: List<String>, apps: List<String>, killSwitch: Boolean): Plan {
        val parsed = validate(source)
        val originalMode = parsed.getString("vpn_mode") ?: "general"
        val original = parsed.getArray("exclusions")?.let { a -> (0 until a.size()).map { a.getString(it) } }.orEmpty()
        val requestedMode = if (enabled) { if (through) "selective" else "general" } else originalMode
        var rules = ((if (!enabled || originalMode == requestedMode) original else emptyList()) +
            if (enabled) SplitRules.normalize(domains.joinToString("\n")).map { SplitRules.core(it)!! } else emptyList()).distinct()
        var selectedApps = if (enabled) apps.distinct().sorted() else emptyList()
        var allowApps = enabled && through && selectedApps.isNotEmpty()
        var mode = requestedMode
        var warning: Int? = null
        if (mode == "selective") {
            when {
                selectedApps.isNotEmpty() && rules.isNotEmpty() -> {
                    // Android's per-app allowlist intersects with destination routing.
                    // The adapter cannot express a union. Protect all traffic and tell
                    // the user explicitly instead of silently losing either ruleset.
                    mode = "general"; rules = emptyList(); selectedApps = emptyList(); allowApps = false
                    warning = R.string.text_applications_and_addresses_are_selected_full_vpn_pro
                }
                selectedApps.isNotEmpty() -> mode = "general"
                rules.isEmpty() -> {
                    mode = "general"
                    warning = R.string.text_no_through_vpn_rules_full_vpn_is_enabled
                }
            }
        }
        val root = linkedMapOf<String, Any>()
        parsed.keySet().forEach { root[it] = parsed.get(listOf(it))!! }
        root["vpn_mode"] = mode
        root["exclusions"] = rules
        root["killswitch_enabled"] = killSwitch
        // Render each top-level value as a valid TOML value. Nested tables become
        // inline tables, so multiline arrays, quoted keys and table comments work.
        val result = root.entries.joinToString("\n", postfix = "\n") { (k, v) -> "${quote(k)} = ${render(v)}" }
        return Plan(result, selectedApps, allowApps, warning)
    }
}
