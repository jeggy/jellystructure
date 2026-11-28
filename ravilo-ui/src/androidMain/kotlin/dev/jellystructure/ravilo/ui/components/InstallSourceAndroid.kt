package dev.jellystructure.ravilo.ui.components

import android.content.Context
import android.os.Build
import dev.jellystructure.ravilo.ui.RaviloAppContext

/** DEB-5851 (FR-4) — `getInstallSourceInfo` on API 30+, `getInstallerPackageName` below (minSdk is 24). */
internal fun installerPackage(context: Context): String? = runCatching {
    val pm = context.packageManager
    if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(context.packageName).installingPackageName
    else @Suppress("DEPRECATION") pm.getInstallerPackageName(context.packageName)
}.getOrNull()

actual fun installSource(): InstallSource = installSourceOf(runCatching { installerPackage(RaviloAppContext.get()) }.getOrNull())
