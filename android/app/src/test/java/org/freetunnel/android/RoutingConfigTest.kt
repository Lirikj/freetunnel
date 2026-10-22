package org.freetunnel.android

import org.junit.Assert.*
import org.junit.Test
import org.tomlj.Toml

class RoutingConfigTest {
    private val source = """
        vpn_mode = "general"
        killswitch_enabled = false
        exclusions = [
          "intranet.example",
        ]
        [endpoint] # table comment
        hostname = "vpn.example.com"
        addresses = ["192.0.2.1:443"]
        username = "test"
        password = 'secret " slash\end'
        upstream_protocol = "http2"
        has_ipv6 = false
        [listener.tun] # another comment
        included_routes = ["0.0.0.0/0", "::/0"]
        excluded_routes = ["192.168.0.0/16"]
        mtu_size = 1350
    """.trimIndent()
    private fun plan(enabled: Boolean = true, through: Boolean = true, domains: List<String> = emptyList(),
                     apps: List<String> = emptyList(), kill: Boolean = true, text: String = source) =
        RoutingConfig.prepare(text, enabled, through, domains, apps, kill)

    @Test fun appsOnlyUseGeneralAndAndroidAllowlist() {
        val result = plan(apps = listOf("test.browser"))
        assertEquals("general", Toml.parse(result.toml).getString("vpn_mode"))
        assertTrue(result.allowApps); assertEquals(listOf("test.browser"), result.apps)
        assertTrue(Toml.parse(result.toml).getArray("exclusions")!!.isEmpty)
    }
    @Test fun domainsOnlyUseSelectiveWithoutAnAndroidAllowlist() {
        val result = plan(domains = listOf("example.org"))
        assertEquals("selective", Toml.parse(result.toml).getString("vpn_mode"))
        assertFalse(result.allowApps); assertTrue(result.apps.isEmpty())
        assertEquals(listOf("example.org"), Toml.parse(result.toml).getArray("exclusions")!!.toList())
    }
    @Test fun mixedThroughRulesProtectAllTrafficWithAnExplicitWarning() {
        val result = plan(domains = listOf("example.org"), apps = listOf("test.browser"))
        val parsed = Toml.parse(result.toml)
        assertEquals("general", parsed.getString("vpn_mode"))
        assertTrue(parsed.getArray("exclusions")!!.isEmpty)
        assertTrue(result.apps.isEmpty()); assertFalse(result.allowApps); assertNotNull(result.warning)
    }
    @Test fun emptySelectiveFallsBackToFullVpnWithWarning() {
        val result = plan()
        assertEquals("general", Toml.parse(result.toml).getString("vpn_mode"))
        assertNotNull(result.warning); assertTrue(result.apps.isEmpty())
    }
    @Test fun bypassRulesWorkForAppsAndDomainsTogether() {
        val result = plan(through = false, domains = listOf("example.org"), apps = listOf("test.browser"))
        assertFalse(result.allowApps)
        assertEquals(listOf("test.browser"), result.apps)
        assertEquals(listOf("intranet.example", "example.org"), Toml.parse(result.toml).getArray("exclusions")!!.toList())
    }
    @Test fun disabledSplitPreservesImportedModeAndExclusions() {
        val original = source.replace("general", "selective")
        val result = plan(enabled = false, domains = listOf("unwanted.example"), apps = listOf("ignored.app"), text = original)
        assertEquals("selective", Toml.parse(result.toml).getString("vpn_mode"))
        assertEquals(listOf("intranet.example"), Toml.parse(result.toml).getArray("exclusions")!!.toList())
        assertTrue(result.apps.isEmpty())
    }
    @Test fun emptyImportedSelectiveAlsoFallsBackWhenUiSplitDisabled() {
        val result = plan(enabled = false, text = source.replace("general", "selective").replace("\"intranet.example\",", ""))
        assertEquals("general", Toml.parse(result.toml).getString("vpn_mode")); assertNotNull(result.warning)
    }
    @Test fun multilineTablesCommentsCredentialsAndRoutesSurvive() {
        val result = plan(enabled = false)
        val parsed = Toml.parse(result.toml)
        assertFalse(parsed.hasErrors())
        assertEquals(Toml.parse(source).getString("endpoint.password"), parsed.getString("endpoint.password"))
        assertEquals(listOf("192.168.0.0/16"), parsed.getArray("listener.tun.excluded_routes")!!.toList())
        assertEquals(1350L, parsed.getLong("listener.tun.mtu_size"))
        assertFalse(parsed.getBoolean("endpoint.has_ipv6")!!)
    }
    @Test fun killSwitchPolicyReplacesOriginalExactlyOnceAndIsIdempotent() {
        val result = plan(enabled = false)
        assertTrue(Toml.parse(result.toml).getBoolean("killswitch_enabled")!!)
        assertEquals(1, Regex("killswitch_enabled").findAll(result.toml).count())
        assertEquals(result.toml, plan(enabled = false, text = result.toml).toml)
        assertFalse(Toml.parse(plan(enabled = false, kill = false).toml).getBoolean("killswitch_enabled")!!)
    }
    @Test fun quotedRoutingKeysCannotBypassReplacement() {
        val result = plan(text = source.replace("killswitch_enabled =", "\"killswitch_enabled\" ="))
        assertTrue(Toml.parse(result.toml).getBoolean("killswitch_enabled")!!)
    }
    @Test fun multilineCertificatesAndNestedInlineTablesSurvive() {
        val text = source.replace("has_ipv6 = false", "has_ipv6 = false\ncertificate = \"\"\"\ncertificate line\n\"\"\"\nextra = { key = [1, 2] }")
        val parsed = Toml.parse(plan(enabled = false, text = text).toml)
        assertEquals("certificate line\n", parsed.getString("endpoint.certificate"))
        assertEquals(listOf(1L, 2L), parsed.getArray("endpoint.extra.key")!!.toList())
    }
    @Test fun substringAndMalformedImportsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { plan(text = "# [endpoint] [listener.tun]") }
        assertThrows(IllegalArgumentException::class.java) { plan(text = source + "\nnot valid") }
        assertThrows(IllegalArgumentException::class.java) { plan(text = source.replace("443", "99999")) }
    }
    @Test fun invalidAndroidTunSettingsAreRejectedBeforeSaving() {
        assertThrows(IllegalStateException::class.java) { plan(text = source.substringBefore("[listener.tun]")) }
        assertThrows(IllegalArgumentException::class.java) { plan(text = source.replace("1350", "0")) }
        assertThrows(IllegalArgumentException::class.java) { plan(text = source.replace("192.168.0.0/16", "192.168.0.0/99")) }
        assertThrows(IllegalArgumentException::class.java) { plan(text = source.replace("has_ipv6 = false", "has_ipv6 = true").replace("1350", "1200")) }
        assertNotNull(plan(text = source.replace("1350", "1200")))
    }
    @Test fun modeChangeDoesNotReverseImportedRules() {
        assertFalse(Toml.parse(plan(domains = listOf("example.org")).toml).getArray("exclusions")!!.toList().contains("intranet.example"))
    }
    @Test fun idnAndAllDesktopSeparatorsAreAccepted() {
        val normalized = SplitRules.normalize("мвд.рф; example.com,*.example.org\n192.0.2.0/24 2001:db8::/32")
        assertEquals(5, normalized.size)
        assertEquals("xn--b1aew.xn--p1ai", SplitRules.core(normalized.first()))
        val result = plan(domains = normalized)
        assertTrue(Toml.parse(result.toml).getArray("exclusions")!!.toList().contains("xn--b1aew.xn--p1ai"))
    }
    @Test fun invalidRulesAreReportedInsteadOfSilentlyDropped() {
        listOf("999.0.0.1", "192.0.2.0/99", "example.com/path", "*.bad..example", "2001:db8::/129").forEach {
            assertThrows(IllegalArgumentException::class.java) { SplitRules.normalize(it) }
        }
    }

    @Test fun defaultDestinationRulesAreRejectedInBothModes() {
        val variants = listOf("0.0.0.0/0", "192.0.2.1/00", "::/0", "2001:db8::1/+0", "0:0:0:0:0:0:0:0/-0")
        for (rule in variants) {
            assertNull(SplitRules.core(rule))
            assertThrows(IllegalArgumentException::class.java) { SplitRules.normalize(rule) }
            assertThrows(IllegalArgumentException::class.java) { plan(through = false, domains = listOf(rule)) }
            assertThrows(IllegalArgumentException::class.java) { plan(text = source.replace("intranet.example", rule)) }
        }
        assertNotNull(plan(enabled = false)) // included_routes defaults remain valid.
    }
    @Test fun excludedRoutesCannotRemoveAnEntireAddressFamily() {
        val exclusions = listOf(
            "\"0.0.0.0/0\"", "\"::/0\"", "\"192.0.2.1/+0\"", "\"2001:db8::1/00\"",
            "\"0.0.0.1/1\", \"128.0.0.9/1\"",
            "\"8000::1/1\", \"::1/1\""
        )
        for (routes in exclusions) assertThrows(IllegalArgumentException::class.java) {
            plan(text = source.replace("\"192.168.0.0/16\"", routes))
        }
        assertFalse(SplitRules.coversAddressFamily(listOf("0.0.0.0/2", "128.0.0.0/1", "0.0.0.0/2")))
        assertFalse(SplitRules.coversAddressFamily(listOf("::ffff:0.0.0.0/96")))
        assertFalse(SplitRules.coversAddressFamily(listOf("::/1", "::ffff:128.0.0.0/97")))
        assertNotNull(plan(enabled = false))
    }
    @Test fun effectiveBypassRulesCannotCoverAnEntireFamilyButSelectiveUnionMay() {
        val halves = listOf("0.0.0.0/1", "128.0.0.0/1")
        assertThrows(IllegalArgumentException::class.java) { plan(through = false, domains = halves) }
        assertEquals("selective", Toml.parse(plan(domains = halves).toml).getString("vpn_mode"))
    }
    @Test fun adapterRequiredTunFieldsAreRequiredBeforeSaving() {
        assertThrows(IllegalStateException::class.java) { plan(text = source.replace("excluded_routes = [\"192.168.0.0/16\"]", "")) }
        assertThrows(IllegalStateException::class.java) { plan(text = source.replace("mtu_size = 1350", "")) }
    }
    @Test fun credentialsAreWrittenAsAssignmentsUnderOrdinaryTableHeaders() {
        val password = "secret\\"
        val text = source.replace("password = 'secret \" slash\\end'", "password = '" + password + "'")
        val result = plan(enabled = false, text = text).toml
        assertTrue(result.contains("[endpoint]\n")); assertTrue(result.contains("[listener.tun]\n"))
        assertFalse(result.contains("endpoint = {"))
        assertEquals(password, Toml.parse(result).getString("endpoint.password"))
        val tables = text + "\n[[extra]]\nvalue = 'one\\'\n[[extra]]\nvalue = 'two'\n"
        val parsed = Toml.parse(plan(enabled = false, text = tables).toml)
        assertEquals("one\\", parsed.getArray("extra")!!.getTable(0).getString("value"))
        assertEquals("two", parsed.getArray("extra")!!.getTable(1).getString("value"))
    }
    @Test fun validationIncludesAutomaticPlatformExclusions() {
        val rules = listOf("0.0.0.0/1", "128.0.0.0/2", "192.0.0.0/3")
        assertFalse(SplitRules.coversAddressFamily(rules))
        assertTrue(SplitRules.coversAddressFamily(rules + RoutingConfig.platformExcludedRoutes))
        assertThrows(IllegalArgumentException::class.java) { plan(through = false, domains = rules) }
        assertThrows(IllegalArgumentException::class.java) {
            plan(text = source.replace("has_ipv6 = false", "has_ipv6 = true")
                .replace("\"192.168.0.0/16\"", rules.joinToString(", ") { "\"$it\"" }))
        }
        assertNotNull(plan(enabled = false))
    }
}
