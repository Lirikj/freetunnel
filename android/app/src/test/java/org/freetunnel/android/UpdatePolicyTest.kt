package org.freetunnel.android

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature

class UpdatePolicyTest {
    private fun candidate(tag: String, draft: Boolean = false, prerelease: Boolean = false): UpdatePolicy.Candidate {
        val version = tag.removePrefix("android-")
        val names = listOf("freetunnel-android-$version.apk", "SHA256SUMS-android.txt", "SHA256SUMS-android.txt.sig")
        return UpdatePolicy.Candidate(tag, draft, prerelease, names.associateWith {
            "https://github.com/${UpdatePolicy.REPOSITORY}/releases/download/$tag/$it"
        })
    }
    private val release get() = UpdatePolicy.select(listOf(candidate("android-1.2.0")), "1.0.0")!!
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val rawKey get() = pair.public.encoded.takeLast(32).toByteArray()
    private val checksum = MessageDigest.getInstance("SHA-256").digest("APK".toByteArray())
    private val hash get() = checksum.joinToString("") { "%02x".format(it) }
    private fun sign(bytes: ByteArray): ByteArray = Signature.getInstance("Ed25519").run {
        initSign(pair.private); update(bytes); sign()
    }
    private fun verify(text: String) = text.toByteArray().let { UpdatePolicy.verifyManifest(release, it, sign(it), rawKey) }

    @Test fun selectsNumericAndroidStableAmongMixedTags() {
        val inputs = listOf(candidate("v99.0.0"), candidate("android-1.0.1"), candidate("android-1.9.0"),
            candidate("android-1.10.0"), candidate("android-90.0.0", prerelease = true), candidate("android-99.0.0", draft = true),
            candidate("android-1.11.0-beta"), candidate("android-1.11.0/other"))
        assertEquals("1.10.0", UpdatePolicy.select(inputs, "1.0.0")!!.version)
        assertNull(UpdatePolicy.select(inputs, "1.10.0"))
    }
    @Test fun rejectsForeignOrAmbiguousAssetUrls() {
        val original = candidate("android-1.2.0")
        val filename = "freetunnel-android-1.2.0.apk"
        val url = original.assets[filename]!!
        for (foreign in listOf(url.replace("dimmmmmmmer", "attacker"), url.replace("android-1.2.0/", "android-1.1.0/"),
                url.replace("https:", "http:"), url + "?x=1", url + "#fragment", url.replace("github.com", "github.com.evil.test"),
                url.replace("github.com", "github.com@evil.test"), url.replace("android-1.2.0/", "android-1.2.0/%2e%2e/"))) {
            assertNull(foreign, UpdatePolicy.select(listOf(original.copy(assets = original.assets + (filename to foreign))), "1.0.0"))
        }
    }
    @Test fun acceptsMatchingSignedManifestAndDigest() {
        assertArrayEquals(checksum, verify("#version=1.2.0\n$hash  ${release.filename}\n"))
        UpdatePolicy.verifyDigest(checksum, checksum.copyOf())
    }
    @Test fun refusesMissingWrongOrRepeatedVersionEvenWithValidSignature() {
        for (text in listOf("$hash  ${release.filename}\n", "#version=1.1.0\n$hash  ${release.filename}\n",
                "#version=1.2.0\n#version=1.1.0\n$hash  ${release.filename}\n")) {
            assertThrows(IllegalArgumentException::class.java) { verify(text) }
        }
    }
    @Test fun refusesBadSignatureAndChangedManifest() {
        val bytes = "#version=1.2.0\n$hash  ${release.filename}\n".toByteArray()
        val signed = sign(bytes)
        signed[0] = (signed[0].toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.verifyManifest(release, bytes, signed, rawKey) }
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.verifyManifest(release, bytes + byteArrayOf(10), sign(bytes), rawKey) }
    }
    @Test fun refusesWrongHashAndAmbiguousOrMissingApkEntries() {
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.verifyDigest(checksum, ByteArray(32)) }
        assertThrows(IllegalArgumentException::class.java) { verify("#version=1.2.0\n$hash  other.apk\n") }
        assertThrows(IllegalArgumentException::class.java) { verify("#version=1.2.0\n$hash  ${release.filename}\n$hash  ${release.filename}\n") }
    }
}
