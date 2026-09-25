package dev.jellystructure.ui

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

// Phase 187 (FR-187-9) — read-only photo per user row, in place of the initials chip when Jellyfin
// has one. Same `.usr-av`/`.av-img` markup as `design/app/ravilo-users.html`'s mockup, and the same
// gradient presets, so a viewer's photo appears identically whether it was set on the TV/phone (via
// Ravilo) or is missing and falling back to initials here — one stored fact (Jellyfin's own user
// Primary image), never two representations that can disagree.
private val USR_GRADIENTS = listOf(
    "linear-gradient(120deg,#7b6ef0,#3fb6f5)", "linear-gradient(120deg,#e0792f,#f5b542)",
    "linear-gradient(120deg,#19d6c6,#2a8cf0)", "linear-gradient(120deg,#2dd49a,#3fb6f5)",
    "linear-gradient(120deg,#e0567a,#7b6ef0)", "linear-gradient(120deg,#e0639a,#b15cd0)",
)
private fun usrInitials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        parts.size >= 2 -> "${parts.first().first()}${parts.last().first()}".uppercase()
        parts.size == 1 -> parts.first().take(2).uppercase()
        else -> "?"
    }
}
private fun usrAvatarChip(userId: String, username: String, avatarUrl: String?): String {
    if (avatarUrl != null) {
        return """<span class="usr-av has-photo"><img class="av-img" src="${avatarUrl.esc()}" alt=""></span>"""
    }
    val gradient = USR_GRADIENTS[(userId.hashCode().let { if (it < 0) -it else it }) % USR_GRADIENTS.size]
    return """<span class="usr-av" style="background:$gradient;">${usrInitials(username).esc()}</span>"""
}

// Phase 148 — "Users & devices" extracted out of the Settings tab rail into its own standalone
// page/route (design/app/ravilo-users.html), living under the sidebar's Ravilo section. Everything
// here (markup + the loadUsersCard/refreshUsersList/loadUserHistory trio) was moved verbatim from
// Settings.kt (Phase 143) — no behavior change, only where it renders.
fun renderRaviloUsers(container: Element, scope: CoroutineScope) {
    container.innerHTML = """
        <div class="pagebar">
          <h1>Users &amp; devices</h1><span class="badge info" style="margin-left:2px">Ravilo · from Jellyfin</span>
          <span class="spacer"></span>
          <span id="users-summary" class="tiny muted" style="margin-right:10px"></span>
          <span id="users-version-chip" class="chip" style="font-size:.72rem;display:none;margin-right:8px" title="This server's own version, and the devices on an older release (a -g… development build is never counted)"></span>
          <button id="users-refresh-btn" class="btn sm ghost">Refresh</button>
        </div>
        <p class="page-sub">Every Jellyfin user with their <b>Ravilo devices</b> and <b>admin web sessions</b> — created, last used, and whether it's connected now. <b>Revoke</b> signs one device/browser out; <b>Sign out everywhere</b> clears all of a user's devices <i>and</i> web sessions. The <b>access</b> line mirrors each user's Jellyfin policy — Ravilo serves only what it permits. <b>Now watching</b> / <b>recently watched</b> come from Jellyfin. A device's <b>version history</b> is dated when this server first saw the version in a request — a TV that stays off updates on its first request after it wakes. Read-only against Jellyfin — edit accounts &amp; policies there.</p>
        <div id="users-list"><span class="muted tiny">Loading…</span></div>
    """.trimIndent()

    scope.launch { loadUsersCard(scope) }
}

private suspend fun loadUsersCard(scope: CoroutineScope) {
    refreshUsersList(scope)
    scope.launch { loadVersionChip() }
    document.getElementById("users-refresh-btn")?.addEventListener("click") {
        scope.launch { refreshUsersList(scope) }
    }
}

/**
 * Phase 185 (FR-185-8) — "what this device can take", plain words. Both ceilings null ⇒ the one unknown
 * state this admin card distinguishes (FR-185-2 also has a structurally-distinct "not measured" for a
 * client that can never report one, e.g. Ravilo Web — not surfaced separately here: stored state alone
 * can't tell the two apart, and "not measured yet" is honest either way — no Ravilo session on the R216
 * build has told us).
 */
private fun decodeCapabilityLine(d: dev.jellystructure.api.OverviewDevice): String {
    val hevc = d.decodeMaxBitrateHevc
    val h264 = d.decodeMaxBitrateH264
    if (hevc == null && h264 == null) return "picture it can take · not measured yet"
    fun mbps(bps: Long) = "${bps / 1_000_000} Mbps"
    val parts = buildList {
        hevc?.let { add("up to ${mbps(it)} HEVC") }
        h264?.let { add("up to ${mbps(it)} H.264") }
    }
    val whenPart = d.decodeMeasuredAt?.let { " · measured ${usersAgo(it)}" } ?: ""
    return "picture it can take · ${parts.joinToString(" · ")}$whenPart"
}

// Phase 224 (FR-224-5) — which build this device runs, from the headers R252 clients send on every
// request. "not reported yet" is the honest state for a client that predates R252 — same idiom as the
// decode line's "not measured yet" above. Phase 259 (FR-259-7) — "· since {first seen}" and the history
// toggle; [vhId] is the id of this device's timeline block (rendered by [versionHistoryBlock]).
private fun appVersionLine(d: dev.jellystructure.api.OverviewDevice, vhId: String): String {
    val platform = when (d.platform) {
        null -> null
        "tv" -> "TV"; "phone" -> "Phone"; "web" -> "Web"; "tizen" -> "Tizen"; "cast" -> "Chromecast"
        else -> d.platform.esc()
    }
    val version = d.appVersion?.let { "Ravilo <b>${it.esc()}</b>" }
    if (version == null && platform == null) return "version · not reported yet"
    val since = d.versionSince?.let { "since ${usersAt(it)}" }
    val toggle = when {
        d.versions.isEmpty() -> ""
        d.versions.size == 1 -> """ <span class="muted">· no update seen</span>"""
        else -> """<span class="usr-vh-btn" data-vh="$vhId">${d.versions.size} versions ▾</span>"""
    }
    return listOfNotNull(version, platform, since).joinToString(" · ") + toggle
}

/** Phase 256 (FR-256-3) — *Unstable connection: 38 reconnects in the last hour*; nothing when the server
 *  did not send the count (the device is under the threshold). */
private fun unstableLine(d: dev.jellystructure.api.OverviewDevice): String {
    val n = d.reconnectsLastHour ?: return ""
    return """<div class="tiny usr-cap unstable"><b>Unstable connection:</b> $n reconnects in the last hour</div>"""
}

/** Phase 256 (FR-256-5) — a device that stopped updating: more than one release behind this backend for
 *  more than seven days. Read-only; the Play-track hint is R293 FR-R293-8's. */
private fun behindLine(d: dev.jellystructure.api.OverviewDevice): String {
    val n = d.releasesBehind ?: return ""
    val since = d.behindSince?.let { " since ${usersAt(it)}" } ?: ""
    val version = d.appVersion?.esc() ?: "?"
    return """<div class="tiny usr-cap behind"><b>Ravilo $version — $n releases behind</b>$since. If this device installs from Google Play, check that its account is a tester on the track releases go to.</div>"""
}

/** Phase 259 (FR-259-7/8) — the timeline, newest first: version · first seen → next first seen (span) · one
 *  note (current / skipped … / dev build / already on it when history began). Closed until toggled. */
private fun versionHistoryBlock(d: dev.jellystructure.api.OverviewDevice, vhId: String): String {
    if (d.versions.size < 2) return ""
    val rows = d.versions.mapIndexed { i, v ->
        val next = d.versions.getOrNull(i - 1)          // newest first ⇒ the row before this one came after it
        val older = d.versions.getOrNull(i + 1)
        val end = next?.firstSeenAt
        val isDev = dev.jellystructure.shared.tv.isRaviloDevBuild(v.appVersion)
        val skipped = if (v.observed && !isDev) dev.jellystructure.shared.tv.raviloSkippedBetween(older?.appVersion, v.appVersion)?.let { "skipped $it" } else null
        val note = when {
            !v.observed -> "already on it when history began · ${usersAt(v.firstSeenAt)}"
            isDev -> "dev build"
            i == 0 -> listOfNotNull("current", skipped).joinToString(" · ")
            else -> skipped ?: ""
        }
        val whenText = when {
            !v.observed -> if (end != null) "→ ${usersAt(end)}" else ""
            end == null -> "${usersAt(v.firstSeenAt)} → now"
            else -> "${usersAt(v.firstSeenAt)} → ${usersAt(end)} <span class=\"muted\">· ${spanLabel(end - v.firstSeenAt)}</span>"
        }
        """<div class="vh-r${if (i == 0) " now" else ""}"><span class="vh-v">${v.appVersion.esc()}</span><span class="vh-when">$whenText</span><span class="vh-note">${note.esc()}</span></div>"""
    }.joinToString("")
    val devFoot = if (d.versions.any { dev.jellystructure.shared.tv.isRaviloDevBuild(it.appVersion) })
        """<div class="vh-foot">A version with a -g… suffix is a development build — never counted as behind.</div>""" else ""
    return """<div class="usr-vh-wrap" id="$vhId"><div class="usr-vh">$rows$devFoot</div></div>"""
}

private fun spanLabel(ms: Long): String {
    val min = ms / 60_000
    return when {
        min < 1 -> "moments"
        min < 60 -> "$min min"
        min < 60 * 48 -> "${min / 60} hours"
        else -> "${min / (60 * 24)} days"
    }
}

/** Phase 259 (FR-259-9) — `latest Ravilo 1.38 · N devices behind` / `· all up to date`; hidden while this
 *  server runs a dev build (no "latest" to compare against). */
private suspend fun loadVersionChip() {
    val chip = document.getElementById("users-version-chip") as? HTMLElement ?: return
    val v = runCatching { dev.jellystructure.api.RaviloApi.getRaviloVersionSummary() }.getOrNull()
    if (v == null || !v.release) { chip.style.display = "none"; return }
    val tail = if (v.behind == 0) "all up to date" else "${v.behind} device${if (v.behind == 1) "" else "s"} behind"
    chip.innerHTML = """latest Ravilo <b class="mono">${v.latest.esc()}</b> <span class="muted">· $tail</span>"""
    chip.style.display = ""
}

private fun usersAgo(epochMs: Long): String = if (epochMs <= 0) "never" else dev.jellystructure.formatRelativeAgo((epochMs / 1000).toString())
private fun usersAt(epochMs: Long): String = if (epochMs <= 0) "—" else dev.jellystructure.formatStoredTs((epochMs / 1000).toString())

// Phase 143 (design addendum) — "Recently watched" per-user lazy pagination. Note: history timestamps
// from the backend are already epoch SECONDS (Jellyfin ISO DatePlayed) — unlike usersAt/usersAgo above,
// which divide by 1000 because device/session timestamps are epoch millis. Do not mix the two helpers.
private val historyNextOffset = mutableMapOf<String, Int>()
private fun formatHistoryTs(epochSec: Long): String = if (epochSec <= 0) "—" else dev.jellystructure.formatStoredTs(epochSec.toString())

private suspend fun loadUserHistory(userId: String, reset: Boolean) {
    val bodyEl = document.getElementById("history-body-$userId") as? HTMLElement ?: return
    val moreBtn = document.getElementById("history-more-$userId") as? HTMLElement
    if (reset) { historyNextOffset[userId] = 0; bodyEl.innerHTML = "" }
    val offset = historyNextOffset[userId] ?: 0
    val page = runCatching { dev.jellystructure.api.RaviloApi.getHistory(userId, offset) }.getOrNull()
    if (page == null) {
        bodyEl.innerHTML = """<span class="tiny" style="color:var(--bad)">Couldn't load history.</span>"""
        moreBtn?.style?.display = "none"
        return
    }
    if (page.entries.isEmpty() && offset == 0) {
        bodyEl.innerHTML = """<span class="tiny muted">No watch history yet.</span>"""
        moreBtn?.style?.display = "none"
        return
    }
    val rowsHtml = page.entries.joinToString("") { e ->
        val range = if (e.firstPlayedAt == e.lastPlayedAt) formatHistoryTs(e.lastPlayedAt)
                    else "${formatHistoryTs(e.firstPlayedAt)} – ${formatHistoryTs(e.lastPlayedAt)}"
        val epPart = e.episodeLabel?.let { " · $it" } ?: ""
        val badge = when {
            !e.finished -> """<span style="color:var(--acc-ink)">▶ stopped at ${e.progressPct ?: 0}%</span>"""
            e.episodeCount > 1 -> "✓ ${e.episodeCount} episodes"
            else -> "✓ finished"
        }
        """<div class="tiny" style="padding:5px 0;border-top:1px solid var(--line)">
             <b>${e.title.esc()}</b>$epPart · $range · $badge
           </div>"""
    }
    bodyEl.innerHTML += rowsHtml
    historyNextOffset[userId] = offset + page.entries.size
    moreBtn?.style?.display = if (page.hasMore) "inline-block" else "none"
}

private suspend fun refreshUsersList(scope: CoroutineScope) {
    val listEl = document.getElementById("users-list") as? HTMLElement ?: return
    val users = runCatching { dev.jellystructure.api.RaviloApi.getOverview() }.getOrNull()
    if (users == null) {
        listEl.innerHTML = """<span class="tiny" style="color:var(--bad)">Couldn't load users & devices.</span>"""
        return
    }
    val deviceCount = users.sumOf { it.devices.size }
    val connectedCount = users.sumOf { u -> u.devices.count { it.connected } }
    (document.getElementById("users-summary") as? HTMLElement)?.textContent =
        "${users.size} users · $deviceCount devices · $connectedCount connected now"

    if (users.isEmpty()) {
        listEl.innerHTML = """<span class="muted tiny">No users have signed in yet.</span>"""
        return
    }

    listEl.innerHTML = users.joinToString("") { u ->
        val p = u.policy
        val badges = buildString {
            if (p.isAdmin) append("""<span class="badge info" style="margin-left:6px">admin</span>""")
            if (!p.allFolders) append("""<span class="badge warn" style="margin-left:6px">restricted</span>""")
            if (u.devices.any { it.isKids }) append("""<span class="badge" style="margin-left:6px">kids</span>""")
        }
        val access = if (p.allFolders) "All libraries" else "${p.libraryCount ?: 0} of ${p.totalLibraries} libraries"
        val tagBits = buildList {
            if (p.blockedTags.isNotEmpty()) add("blocked tags " + p.blockedTags.joinToString(", "))
            if (p.allowedTags.isNotEmpty()) add("allowed tags " + p.allowedTags.joinToString(", "))
            p.maxRating?.let { add("max rating $it") }
        }
        // Phase 258 — the line reads the LIVE policy, so it must say when it is not the policy a device
        // enforces (FR-258-6: one clause, no per-device detail), and must not claim "All libraries" when
        // Jellyfin simply did not answer (dev review item 2).
        val accessLine = when {
            !p.known -> "policy unknown · Jellyfin did not answer"
            p.stale -> (listOf(access) + tagBits).joinToString(" · ") + " · a device is still on an older policy"
            else -> (listOf(access) + tagBits).joinToString(" · ")
        }

        val deviceRows = if (u.devices.isEmpty()) """<div class="tiny muted" style="padding:6px 0">No Ravilo devices.</div>""" else u.devices.joinToString("") { d ->
            val vhId = "vh-${u.userId.filter { it.isLetterOrDigit() }}-${d.deviceId.filter { it.isLetterOrDigit() }}"   // Phase 259
            val connBadge = if (d.connected) """<span class="badge ok" style="margin-left:6px">connected</span>""" else ""
            val playing = d.nowPlaying?.let { """<div class="tiny" style="color:var(--acc-ink)">▶ playing ${it.esc()}</div>""" } ?: ""
            // Phase 177 §FR-177-5 — a clean session is never badged; only rebuffers/dropped frames are.
            val quality = d.recentQuality?.takeIf { it.hasIssue }?.let { q ->
                val bits = buildList {
                    if (q.rebufferCount > 0) add("${q.rebufferCount} rebuffer${if (q.rebufferCount != 1) "s" else ""} (${q.rebufferMs / 1000}s)")
                    if (q.droppedFrames > 0) add("${q.droppedFrames} dropped frames")
                }.joinToString(", ")
                """<div class="tiny" style="margin-top:2px"><span class="badge warn">quality</span> $bits · ${q.linkKind}${if (q.linkMbps > 0) " ${q.linkMbps} Mbps" else ""}${if (!q.directPlay) " · transcoding" else ""}</div>"""
            } ?: ""
            """<div class="row center" style="padding:7px 0;border-top:1px solid var(--line)">
                 <div style="flex:1;min-width:0">
                   <b class="tiny">${d.name.esc()}</b>$connBadge
                   <div class="tiny muted">created ${usersAt(d.createdAt)} · last seen ${usersAgo(d.lastSeen)}</div>
                   <div class="tiny muted usr-cap">${decodeCapabilityLine(d)}</div>
                   <div class="tiny muted usr-cap">${appVersionLine(d, vhId)}</div>
                   ${versionHistoryBlock(d, vhId)}
                   ${unstableLine(d)}
                   ${behindLine(d)}
                   $playing
                   $quality
                 </div>
                 <button class="btn sm ghost users-revoke-device" data-device="${d.deviceId}" data-user="${u.userId}">Revoke</button>
               </div>"""
        }

        val sessionRows = if (u.sessions.isEmpty()) "" else """
            <div class="tiny muted" style="margin-top:10px;margin-bottom:2px">Admin web sessions</div>
        """ + u.sessions.joinToString("") { s ->
            val cur = if (s.isCurrent) """<span class="badge info" style="margin-left:6px">this session</span>""" else ""
            """<div class="row center" style="padding:7px 0;border-top:1px solid var(--line)">
                 <div style="flex:1;min-width:0">
                   <b class="tiny">Web session</b>$cur
                   <div class="tiny muted">created ${usersAt(s.createdAt)} · last used ${usersAgo(s.lastUsedAt)}</div>
                 </div>
                 <button class="btn sm ghost users-revoke-session" data-id="${s.id}" data-current="${s.isCurrent}">Revoke</button>
               </div>"""
        }

        """<div class="card" style="margin-bottom:12px;padding:14px 16px">
             <div class="row center" style="margin-bottom:4px">
               ${usrAvatarChip(u.userId, u.username, u.avatarUrl)}
               <b>${u.username.esc()}</b>$badges
               <span class="spacer"></span>
               <button class="btn sm ghost users-signout-all" data-user="${u.userId}" style="color:var(--bad)">Sign out everywhere</button>
             </div>
             <div class="tiny muted" style="margin-bottom:6px">${accessLine.esc()}</div>
             $deviceRows
             $sessionRows
             <div style="margin-top:10px">
               <button class="tiny users-history-toggle" data-user="${u.userId}" style="background:none;border:none;color:var(--acc-ink);cursor:pointer;padding:0">Recently watched ▾</button>
               <div id="history-body-${u.userId}" style="display:none;margin-top:6px"></div>
               <button id="history-more-${u.userId}" class="btn sm ghost users-history-more" data-user="${u.userId}" style="display:none;margin-top:6px">Show more</button>
               <div id="history-footer-${u.userId}" class="tiny muted" style="display:none;margin-top:6px">Full history lives in Jellyfin.</div>
             </div>
           </div>"""
    }

    // Phase 259 (FR-259-7) — the history toggle; all rows start closed, nothing is remembered across loads.
    listEl.querySelectorAll(".usr-vh-btn").let { nodes ->
        for (i in 0 until nodes.length) {
            val btn = nodes.item(i) as? HTMLElement ?: continue
            btn.addEventListener("click") {
                val id = btn.getAttribute("data-vh") ?: return@addEventListener
                val wrap = document.getElementById(id) as? HTMLElement ?: return@addEventListener
                val open = wrap.classList.toggle("open")
                btn.textContent = btn.textContent?.replace(if (open) "▾" else "▴", if (open) "▴" else "▾")
            }
        }
    }
    listEl.querySelectorAll(".users-revoke-device").let { nodes ->
        for (i in 0 until nodes.length) {
            val btn = nodes.item(i) as? HTMLElement ?: continue
            btn.addEventListener("click") {
                val deviceId = btn.getAttribute("data-device") ?: return@addEventListener
                val userId = btn.getAttribute("data-user") ?: return@addEventListener
                if (!window.confirm("Revoke this device? It will need to sign in again.")) return@addEventListener
                scope.launch {
                    runCatching { dev.jellystructure.api.RaviloApi.revokeDevice(deviceId, userId) }
                    refreshUsersList(scope)
                }
            }
        }
    }
    listEl.querySelectorAll(".users-revoke-session").let { nodes ->
        for (i in 0 until nodes.length) {
            val btn = nodes.item(i) as? HTMLElement ?: continue
            btn.addEventListener("click") {
                val id = btn.getAttribute("data-id") ?: return@addEventListener
                val isCurrent = btn.getAttribute("data-current") == "true"
                val msg = if (isCurrent) "Revoke your OWN session? You will be signed out immediately."
                          else "Revoke this admin web session?"
                if (!window.confirm(msg)) return@addEventListener
                scope.launch {
                    runCatching { dev.jellystructure.api.RaviloApi.revokeSession(id) }
                    if (isCurrent) window.location.reload() else refreshUsersList(scope)
                }
            }
        }
    }
    listEl.querySelectorAll(".users-signout-all").let { nodes ->
        for (i in 0 until nodes.length) {
            val btn = nodes.item(i) as? HTMLElement ?: continue
            btn.addEventListener("click") {
                val userId = btn.getAttribute("data-user") ?: return@addEventListener
                if (!window.confirm("Sign this user out everywhere — every device AND every admin web session? This can't be undone.")) return@addEventListener
                scope.launch {
                    runCatching { dev.jellystructure.api.RaviloApi.signOutAll(userId) }
                    refreshUsersList(scope)
                }
            }
        }
    }
    // Phase 143 (design addendum) — "Recently watched" stays collapsed and unfetched until the admin
    // actually opens it: the one section that reads Jellyfin live, kept lazy per-user (never fanned
    // out across every user on this page's load).
    listEl.querySelectorAll(".users-history-toggle").let { nodes ->
        for (i in 0 until nodes.length) {
            val btn = nodes.item(i) as? HTMLElement ?: continue
            btn.addEventListener("click") {
                val userId = btn.getAttribute("data-user") ?: return@addEventListener
                val bodyEl = document.getElementById("history-body-$userId") as? HTMLElement ?: return@addEventListener
                val footerEl = document.getElementById("history-footer-$userId") as? HTMLElement
                val collapsed = bodyEl.style.display == "none"
                if (collapsed) {
                    bodyEl.style.display = "block"
                    footerEl?.style?.display = "block"
                    btn.textContent = "Recently watched ▴"
                    if (bodyEl.innerHTML.isBlank()) {
                        bodyEl.innerHTML = """<span class="tiny muted">Loading…</span>"""
                        scope.launch { loadUserHistory(userId, reset = true) }
                    }
                } else {
                    bodyEl.style.display = "none"
                    footerEl?.style?.display = "none"
                    btn.textContent = "Recently watched ▾"
                }
            }
        }
    }
    listEl.querySelectorAll(".users-history-more").let { nodes ->
        for (i in 0 until nodes.length) {
            val btn = nodes.item(i) as? HTMLElement ?: continue
            btn.addEventListener("click") {
                val userId = btn.getAttribute("data-user") ?: return@addEventListener
                scope.launch { loadUserHistory(userId, reset = false) }
            }
        }
    }
}
