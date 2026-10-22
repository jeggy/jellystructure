package dev.jellystructure.media

import kotlinx.coroutines.delay

/**
 * DEB-2051 (FR-8) — after a verified conversion, the title's identity follows its video file to the `.mkv`, using the
 * 316 mover's ports so each effect is the one the mover already performs and tests with fakes:
 *
 * 1. watch data is read now (the old Jellyfin item may be gone afterwards);
 * 2. our own record gets the new path — and the new tracks, so the language the conversion wrote is what the page reads;
 * 3. Radarr is sent `RescanMovie`, and its file link is read until it names the `.mkv` (it has no API to set one: when
 *    it does not, the job says so and the repair is a person in Radarr — FR-314-12);
 * 4. the folder's artwork and NFO are re-asserted at once, because Radarr takes what it had adopted with the old path
 *    (FR-314-13);
 * 5. Jellyfin is told (new path `Created`, old `Deleted`), the item found **at** the new path, each viewer's watch data
 *    put back where the new item lacks it, and the stored `jellyfinId` remapped if Jellyfin gave a new one.
 */
class ConversionIdentity(
    private val ports: LooseFilmPorts,
    private val radarrPasses: Int = 6,
    private val radarrSettleMs: Long = 10_000L,
) {
    suspend fun move(film: LooseFilm, newVideo: String): ConversionIdentityResult {
        val steps = ArrayList<LooseStep>()
        fun step(name: String, ok: Boolean, detail: String) { steps += LooseStep(name, ok, detail) }
        val newName = newVideo.substringAfterLast('/')

        val userData = film.jellyfinId?.let { runCatching { ports.readUserData(it) }.getOrNull() }.orEmpty()
        step("check", true, "${userData.size} ${if (userData.size == 1) "viewer has" else "viewers have"} watch data")

        // ── our own record first: the path is true on disk now, whatever the outside services do next ──
        film.mediaId?.let { ports.updateMedia(it, newVideo, null) }
        step("records", true, "Path updated to $newName")

        // ── Radarr ──
        val radarrTaken: Boolean? = when (film.radarr) {
            RadarrPlan.UPDATE -> {
                val id = film.radarrId
                if (id == null) { step("radarr", false, "Radarr has no id for this film"); false }
                else {
                    val asked = ports.radarrRescan(id)
                    var linked = false
                    repeat(radarrPasses) { pass ->
                        if (linked) return@repeat
                        if (pass > 0) delay(radarrSettleMs)
                        linked = ports.radarrFilePath(id)?.substringAfterLast('/') == newName
                    }
                    step("radarr", linked && asked,
                        if (linked) "Radarr's file link names $newName"
                        else "Radarr hasn't taken the new file — re-link it in Radarr")
                    linked
                }
            }
            RadarrPlan.OTHER_FILE -> { step("radarr", true, "Left alone: Radarr maps this film to another file"); null }
            RadarrPlan.NOT_MANAGED -> { step("radarr", true, "Not in Radarr"); null }
            else -> { step("radarr", true, "Radarr isn't set up"); null }
        }

        // ── the artwork Radarr takes with the old path ──
        val back = runCatching { ports.reassertAssets(film, newVideo) }.getOrDefault(emptyList())
        step("artwork", true, if (back.isEmpty()) "Its images and NFO are in the folder" else "Put ${back.joinToString(", ")} back")

        // ── Jellyfin ──
        ports.jellyfinNotify(film.key, film.targetFolder)
        val found = ports.findJellyfinItem(newVideo)
        val foundAt = found?.let { ports.jellyfinItemPath(it) }
        val newId = found?.takeIf { foundAt == newVideo }
        if (newId == null) step("jellyfin", false, "Jellyfin hasn't found it at the new path yet")
        else {
            var restored = 0
            for ((user, data) in userData) {
                if (data.isEmpty()) continue
                val now = runCatching { ports.readUserDataFor(user, newId) }.getOrNull()
                if (now == null || now.isEmpty()) { if (ports.writeUserData(user, newId, data)) restored++ }
            }
            step("jellyfin", true, "Found it" + (if (newId != film.jellyfinId) " under a new id" else "") +
                (if (restored > 0) "; watch data put back for $restored ${if (restored == 1) "viewer" else "viewers"}" else ""))
            val remap = if (film.jellyfinId != null && newId != film.jellyfinId) ports.remapJellyfinId(film.jellyfinId, newId) else RemapResult.NONE
            if (film.mediaId != null && newId != film.jellyfinId) ports.updateMedia(film.mediaId, newVideo, newId)
            if (remap.count > 0) step("records", true, "${remap.count} rows moved to the new id")
            film.mediaId?.let {
                if (remap.count > 0 && film.jellyfinId != null) ports.history(it, "Converted to MKV: ${summary(steps)}", movedRowsSnapshot(film.jellyfinId, newId, remap.rows))
                else ports.history(it, "Converted to MKV: ${summary(steps)}")
            }
            return ConversionIdentityResult(true, radarrTaken, steps.toList())
        }
        film.mediaId?.let { ports.history(it, "Converted to MKV: ${summary(steps)}") }
        return ConversionIdentityResult(false, radarrTaken, steps.toList())
    }

    private fun summary(steps: List<LooseStep>) = steps.joinToString(" · ") { "${it.step} ${if (it.ok) "✓" else "✗"} ${it.detail}" }
}
