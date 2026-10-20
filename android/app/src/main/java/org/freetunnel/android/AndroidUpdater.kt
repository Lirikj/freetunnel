package org.freetunnel.android

import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.net.toUri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONArray
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors

/** Process-local UI state; all network, hashing and session writes use one worker. */
object AndroidUpdater {
    enum class State { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, READY, INSTALLING, ERROR }
    @Volatile var state = State.IDLE; private set
    @Volatile var release: UpdatePolicy.Release? = null; private set
    @Volatile var progress = 0; private set
    private var observer: Runnable? = null
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var verifiedFile: File? = null
    private var expectedDigest: ByteArray? = null

    fun observe(callback: Runnable?) { observer = callback }
    private fun publish(value: State) { state = value; main.post { observer?.run() } }
    private fun busy() = state in listOf(State.CHECKING, State.DOWNLOADING, State.INSTALLING)

    fun check() {
        if (busy()) return
        publish(State.CHECKING)
        worker.execute {
            runCatching {
                verifiedFile?.delete(); verifiedFile = null; expectedDigest = null
                val candidates = mutableListOf<UpdatePolicy.Candidate>()
                for (page in 1..10) {
                    val bytes = read("https://api.github.com/repos/${UpdatePolicy.REPOSITORY}/releases?per_page=100&page=$page", 2_097_152, false)
                    val array = JSONArray(bytes.toString(Charsets.UTF_8))
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        val assets = item.getJSONArray("assets")
                        val pairs = (0 until assets.length()).map { assets.getJSONObject(it) }
                            .map { it.getString("name") to it.getString("browser_download_url") }
                        // Duplicate asset names are ambiguous and cannot be selected.
                        if (pairs.map { it.first }.distinct().size != pairs.size) continue
                        candidates.add(UpdatePolicy.Candidate(item.getString("tag_name"), item.getBoolean("draft"),
                            item.getBoolean("prerelease"), pairs.toMap()))
                    }
                    if (array.length() < 100) break
                    check(page < 10) { "Release history is too large" }
                }
                release = UpdatePolicy.select(candidates, BuildConfig.VERSION_NAME)
                publish(if (release == null) State.CURRENT else State.AVAILABLE)
            }.onFailure { release = null; publish(State.ERROR) }
        }
    }

    fun download(activity: Activity) {
        val selected = release ?: return
        if (busy()) return
        val app = activity.applicationContext
        progress = 0
        publish(State.DOWNLOADING)
        worker.execute {
            var output: File? = null
            runCatching {
                require(UpdatePolicy.trustedAsset(selected.manifest, selected.tag, "SHA256SUMS-android.txt"))
                require(UpdatePolicy.trustedAsset(selected.signature, selected.tag, "SHA256SUMS-android.txt.sig"))
                require(UpdatePolicy.trustedAsset(selected.apk, selected.tag, selected.filename))
                val manifest = read(selected.manifest, 65_536, true)
                val signature = read(selected.signature, 64, true)
                val publicKey = Base64.getDecoder().decode(BuildConfig.RELEASE_SIGNING_KEY)
                require(publicKey.size == 44)
                val expected = UpdatePolicy.verifyManifest(selected, manifest, signature, publicKey.copyOfRange(12, 44))
                val directory = File(app.cacheDir, "updates").apply { check(isDirectory || mkdirs()) }
                directory.listFiles()?.forEach { it.delete() }
                output = File.createTempFile("verified-", ".apk", directory)
                val digest = MessageDigest.getInstance("SHA-256")
                output!!.outputStream().use { destination ->
                    transfer(selected.apk, 524_288_000, true) { input, size ->
                        copyApk(input, destination, size, digest)
                    }
                }
                UpdatePolicy.verifyDigest(expected, digest.digest())
                @Suppress("DEPRECATION")
                val info = app.packageManager.getPackageArchiveInfo(output!!.path, 0) ?: error("Invalid APK")
                @Suppress("DEPRECATION")
                val installed = app.packageManager.getPackageInfo(app.packageName, 0)
                require(info.packageName == app.packageName && info.versionName == selected.version)
                @Suppress("DEPRECATION")
                val newer = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode > installed.longVersionCode else info.versionCode > installed.versionCode
                require(newer) { "APK is not newer than this app" }
                verifiedFile = output
                expectedDigest = expected
                publish(State.READY)
            }.onFailure { output?.delete(); verifiedFile = null; expectedDigest = null; publish(State.ERROR) }
        }
    }

    private fun copyApk(input: InputStream, output: OutputStream, size: Long, digest: MessageDigest) {
        val bytes = ByteArray(65_536)
        var total = 0L
        var last = -1
        while (true) {
            val count = input.read(bytes)
            if (count < 0) break
            total += count
            require(total <= 524_288_000) { "APK too large" }
            digest.update(bytes, 0, count)
            output.write(bytes, 0, count)
            val next = if (size > 0) ((total * 100) / size).coerceAtMost(99).toInt() else 0
            if (next != last) { progress = next; last = next; main.post { observer?.run() } }
        }
        require(total > 0 && (size < 0 || total == size)) { "Incomplete APK" }
    }

    fun install(activity: Activity) {
        if (state != State.READY) return
        if (!activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${activity.packageName}".toUri()))
            return
        }
        val file = verifiedFile ?: return
        val expected = expectedDigest ?: return
        val app = activity.applicationContext
        publish(State.INSTALLING)
        worker.execute {
            var sessionId: Int? = null
            val installer = app.packageManager.packageInstaller
            runCatching {
                // Recheck private cached bytes immediately before handing them to Android.
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input -> val bytes = ByteArray(65_536); while (true) {
                    val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count)
                } }
                UpdatePolicy.verifyDigest(expected, digest.digest())
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName)
                    setSize(file.length())
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
                val id = installer.createSession(params)
                sessionId = id
                installer.openSession(id).use { session ->
                    session.openWrite("base.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; session.fsync(out) }
                    // API 35 requires a mutable callback. It targets a private explicit
                    // activity; only the system-held PendingIntent can deliver results.
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val options = if (Build.VERSION.SDK_INT >= 35) ActivityOptions.makeBasic().apply {
                        setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    }.toBundle() else null
                    val result = PendingIntent.getActivity(app, id, Intent(app, InstallResultActivity::class.java), flags, options)
                    session.commit(result.intentSender)
                }
            }.onFailure { sessionId?.let { runCatching { installer.abandonSession(it) } }; publish(State.ERROR) }
        }
    }

    fun installationFinished(success: Boolean) {
        verifiedFile?.delete(); verifiedFile = null; expectedDigest = null
        publish(if (success) State.CURRENT else State.ERROR)
    }

    private fun read(url: String, maximum: Int, asset: Boolean): ByteArray = transfer(url, maximum.toLong(), asset) { input, _ ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= maximum) { "Response too large" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    private fun <T> transfer(url: String, maximum: Long, asset: Boolean, action: (InputStream, Long) -> T): T {
        var current = URI(url)
        repeat(6) { redirect ->
            require(current.scheme == "https" && current.rawUserInfo == null && current.rawFragment == null && current.port in listOf(-1, 443))
            val hosts = if (asset) setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com") else setOf("api.github.com")
            require(current.host in hosts) { "Untrusted download host" }
            val connection = current.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000; connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "FreeTunnel-Android/${BuildConfig.VERSION_NAME}")
            if (!asset) connection.setRequestProperty("Accept", "application/vnd.github+json")
            try {
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    require(asset && redirect < 5) { "Unexpected redirect" }
                    current = current.resolve(connection.getHeaderField("Location") ?: error("Missing redirect"))
                } else {
                    require(status == 200) { "HTTP $status" }
                    require(connection.contentLengthLong <= maximum) { "Response too large" }
                    return connection.inputStream.use { action(it, connection.contentLengthLong) }
                }
            } finally { connection.disconnect() }
        }
        error("Too many redirects")
    }
}
