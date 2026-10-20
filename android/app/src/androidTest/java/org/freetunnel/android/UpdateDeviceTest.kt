package org.freetunnel.android

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class UpdateDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun field(name: String, value: Any?) {
        AndroidUpdater::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, value)
    }
    private var copy: File? = null
    private var activity: MainActivity? = null
    private fun eventually(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        assertTrue("Condition did not become true", condition())
    }
    @After fun cleanup() {
        context.packageManager.packageInstaller.mySessions.forEach { runCatching { context.packageManager.packageInstaller.abandonSession(it.sessionId) } }
        copy?.delete()
        field("verifiedFile", null); field("expectedDigest", null); field("state", AndroidUpdater.State.IDLE)
        activity?.let { instrumentation.runOnMainSync { it.finish() } }
    }
    @Test fun installCallbackIsPrivateAndAndroidAsksBeforeReplacingApp() {
        assertFalse(context.packageManager.getActivityInfo(ComponentName(context, InstallResultActivity::class.java), 0).exported)
        assertTrue("Grant REQUEST_INSTALL_PACKAGES appop before device tests", context.packageManager.canRequestPackageInstalls())
        // Exercise the PackageInstaller boundary with this already-installed test
        // APK. Crypto policy is covered separately with signed, tampered inputs.
        copy = File.createTempFile("installer-test-", ".apk", context.cacheDir).also {
            File(context.applicationInfo.sourceDir).copyTo(it, overwrite = true)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        copy!!.inputStream().use { input -> val bytes = ByteArray(65_536); while (true) {
            val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count)
        } }
        field("verifiedFile", copy); field("expectedDigest", digest.digest()); field("state", AndroidUpdater.State.READY)
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrumentation.runOnMainSync { AndroidUpdater.install(activity!!) }
        eventually { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("packageinstaller") == true }
        val root = instrumentation.uiAutomation.rootInActiveWindow!!
        val cancel = (root.findAccessibilityNodeInfosByText("Cancel") + root.findAccessibilityNodeInfosByText("CANCEL") +
                root.findAccessibilityNodeInfosByText("Отмена") + root.findAccessibilityNodeInfosByText("ОТМЕНА")).firstOrNull()
        assertNotNull("Android must offer cancellation", cancel)
        assertTrue(cancel!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        eventually { AndroidUpdater.state == AndroidUpdater.State.ERROR }
        @Suppress("DEPRECATION")
        assertEquals(BuildConfig.VERSION_NAME, context.packageManager.getPackageInfo(context.packageName, 0).versionName)
    }
}
