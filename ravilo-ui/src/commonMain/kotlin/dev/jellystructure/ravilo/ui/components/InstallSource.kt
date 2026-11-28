package dev.jellystructure.ravilo.ui.components

/** DEB-5851 (FR-4) — where this install came from, which decides the second line of the update notice. */
enum class InstallSource { GooglePlay, Download }

/** The package name Google Play reports as an app's installer. */
const val PLAY_INSTALLER = "com.android.vending"

/** FR-4 — Google Play, or a download: anything else, or nothing, is a download. */
fun installSourceOf(installerPackage: String?): InstallSource =
    if (installerPackage == PLAY_INSTALLER) InstallSource.GooglePlay else InstallSource.Download

/** Android reads the installer package; everywhere else this is a download (the Linux `.flatpak`, a `.wgt` is not asked). */
expect fun installSource(): InstallSource
