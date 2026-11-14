package com.example.domain.upi

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

data class InstalledUpiApp(
    val packageName: String,
    val appName: String,
)

object UpiIntentLauncher {
    fun discoverInstalledUpiApps(context: Context): List<InstalledUpiApp> {
        val probeUri = Uri.parse("upi://pay?pa=probe@upi&pn=Probe&cu=INR")
        val intent = Intent(Intent.ACTION_VIEW, probeUri)
        val pm = context.packageManager
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        return resolved
            .filter { it.activityInfo?.packageName != context.packageName }
            .map { info ->
                InstalledUpiApp(
                    packageName = info.activityInfo.packageName,
                    appName = info.loadLabel(pm).toString(),
                )
            }
            .distinctBy { it.packageName }
    }

    fun buildUpiPaymentIntent(signedUpiUri: String, targetPackage: String? = null): Intent {
        val uri = Uri.parse(signedUpiUri)
        require(uri.scheme.equals("upi", ignoreCase = true)) {
            "Only official upi:// scheme intents from PSP backend are allowed"
        }
        return Intent(Intent.ACTION_VIEW, uri).apply {
            if (!targetPackage.isNullOrBlank()) {
                setPackage(targetPackage)
            }
        }
    }
}
