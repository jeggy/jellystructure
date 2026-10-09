package dev.jellystructure.ravilo.ui.seams

import kotlin.concurrent.Volatile

/**
 * Phase 309 (FR-309-6, owner 2026-10-07: *"also for Chromecast devices"*) — the Cast device this app is linked to now
 * (Google's Cast device id, the one receivers report as theirs), or null. Set by the platform's Cast sender when a
 * session starts or resumes and cleared when it ends; read by the detail page's early encode, which is then made for
 * that device's receiver (the one Play will hand the film to) instead of for this phone.
 *
 * While cast receivers are served by Jellyfin (313's cast fallback, until the receiver stall is diagnosed) the server
 * answers such a prewarm `none` with that reason; nothing changes here when it lifts.
 */
object CastTargetHint {
    @Volatile var castDeviceId: String? = null
}
