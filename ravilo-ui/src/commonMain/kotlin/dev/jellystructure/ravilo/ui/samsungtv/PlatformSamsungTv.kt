package dev.jellystructure.ravilo.ui.samsungtv

/**
 * DEB-4843 (FR-1) — the Android phone's [SamsungTvPlatform], or null everywhere else: a TV, Tizen, the web and the desktops get no
 * Samsung TV row. The platform decides; a Profile page that finds null draws nothing.
 */
expect fun platformSamsungTv(): SamsungTvPlatform?
