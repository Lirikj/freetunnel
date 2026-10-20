package org.freetunnel.android

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle

/** System installation consent is delivered only through our explicit PendingIntent. */
class InstallResultActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirmation == null) {
                AndroidUpdater.installationFinished(false)
            } else if (savedInstanceState == null) {
                runCatching { startActivity(confirmation) }.onFailure { AndroidUpdater.installationFinished(false) }
            }
            finish()
        } else {
            AndroidUpdater.installationFinished(status == PackageInstaller.STATUS_SUCCESS)
            finish()
        }
    }

}
