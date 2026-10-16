package dev.jellystructure.ravilo.ui.desktop

import dev.jellystructure.ravilo.ui.components.CheckedAt
import dev.jellystructure.ravilo.ui.components.MacInstallState
import dev.jellystructure.ravilo.ui.components.SoftwareUpdateModel
import dev.jellystructure.ravilo.ui.components.UpdateBlockState
import dev.jellystructure.shared.tv.parseRaviloRelease
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * R386 (FR-R386-7) — what the Software update block shows, as a pure function of what [UpdateCheck] knows: the last
 * check's decision and Sparkle's last report. **Nothing here is derived optimistically**: a state appears only when
 * one of those two said so. A null answer is state 10 — a development build, or any platform but the Mac — and the block,
 * the dot and the row are then absent, not greyed and not empty.
 *
 * The order is the order of authority: where the app runs from (FR-R386-5) beats everything, because Sparkle is never
 * asked there; then Sparkle's own report, because it is what is actually downloading or installing; then the check.
 */
object UpdateView {
    fun of(status: UpdateStatus, autoDownload: Boolean, app: String, isMac: Boolean, checked: (Long) -> CheckedAt): SoftwareUpdateModel? {
        if (!isMac) return null
        val release = parseRaviloRelease(app.trim().removePrefix("v")) ?: return null   // a development build: no block
        fun model(block: UpdateBlockState) = SoftwareUpdateModel(block, release.toString(), autoDownload)

        status.cannotUpdateFrom?.let { return model(UpdateBlockState.CannotUpdate(fromDiskImage = it == MacInstallState.ON_VOLUME)) }

        val s = status.sparkle
        val sparkleBlock: UpdateBlockState? = when (s.phase) {
            SparkleSnapshot.Phase.INSTALLING -> UpdateBlockState.Handover(s.version)
            SparkleSnapshot.Phase.READY -> UpdateBlockState.Ready(s.version, notesUrl(s.version))
            SparkleSnapshot.Phase.FAILED -> UpdateBlockState.Failed(signature = s.failure == SparkleSnapshot.Failure.SIGNATURE)
            SparkleSnapshot.Phase.DOWNLOADING ->
                if (autoDownload) UpdateBlockState.Downloading(s.version, s.bytes, s.total) else UpdateBlockState.Offer(s.version)
            SparkleSnapshot.Phase.AVAILABLE ->
                if (autoDownload) UpdateBlockState.Downloading(s.version, s.bytes, s.total) else UpdateBlockState.Offer(s.version)
            // Sparkle's own window is up (D9): the offer is still the viewer's to take again if the window is closed.
            SparkleSnapshot.Phase.WINDOW -> UpdateBlockState.Offer(s.version)
            SparkleSnapshot.Phase.CHECKING -> UpdateBlockState.Checking
            else -> null
        }
        if (sparkleBlock != null) return model(sparkleBlock)

        val plan = status.plan ?: return model(UpdateBlockState.Checking)   // before the first check has finished
        if (status.checking) return model(UpdateBlockState.Checking)
        val last = status.checkedAt?.let(checked)
        return when (plan) {
            UpdatePlan.NoBlock -> null
            UpdatePlan.UpToDate -> model(UpdateBlockState.UpToDate(last))
            is UpdatePlan.HeldBack -> model(UpdateBlockState.HeldBack(plan.latest.toString(), plan.server.toString()))
            UpdatePlan.Offline -> model(UpdateBlockState.Offline(last))
            is UpdatePlan.AskSparkle -> when (s.phase) {
                // Sparkle looked and the feed's release is not newer than this app: nothing to offer.
                SparkleSnapshot.Phase.NOT_FOUND -> model(UpdateBlockState.UpToDate(last))
                // No Sparkle in this bundle: there is no updater to show.
                SparkleSnapshot.Phase.UNAVAILABLE -> null
                // The check was asked for; Sparkle has not begun.
                else -> model(UpdateBlockState.Checking)
            }
        }
    }

    /** The tag's GitHub release page (D7); the latest release's page when the version is not a plain release. */
    fun notesUrl(version: String): String =
        parseRaviloRelease(version.trim().removePrefix("v"))?.let(UpdateRules::releasePageOf) ?: "https://github.com/jeggy/jellystructure/releases/latest"

    /** [CheckedAt] in the viewer's own formats: the time of day, and the date when it was not today. */
    fun checkedAt(epochMs: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): CheckedAt {
        val then = Instant.ofEpochMilli(epochMs).atZone(zone)
        val today = then.toLocalDate() == LocalDate.ofInstant(Instant.ofEpochMilli(now), zone)
        return CheckedAt(
            today = today,
            date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(then),
            time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(then),
        )
    }
}
