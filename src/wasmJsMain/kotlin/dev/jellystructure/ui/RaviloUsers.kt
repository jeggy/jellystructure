package dev.jellystructure.ui

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

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
          <button id="users-refresh-btn" class="btn sm ghost">Refresh</button>
        </div>
        <p class="page-sub">Every Jellyfin user with their <b>Ravilo devices</b> and <b>admin web sessions</b> — created, last used, and whether it's connected now. <b>Revoke</b> signs one device/browser out; <b>Sign out everywhere</b> clears all of a user's devices <i>and</i> web sessions. The <b>access</b> line mirrors each user's Jellyfin policy — Ravilo serves only what it permits. <b>Now watching</b> / <b>recently watched</b> come from Jellyfin. Read-only against Jellyfin — edit accounts &amp; policies there.</p>
        <div id="users-list"><span class="muted tiny">Loading…</span></div>
    """.trimIndent()

    scope.launch { loadUsersCard(scope) }
}

private suspend fun loadUsersCard(scope: CoroutineScope) {
    refreshUsersList(scope)
    document.getElementById("users-refresh-btn")?.addEventListener("click") {
        scope.launch { refreshUsersList(scope) }
    }
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
        val accessLine = (listOf(access) + tagBits).joinToString(" · ")

        val deviceRows = if (u.devices.isEmpty()) """<div class="tiny muted" style="padding:6px 0">No Ravilo devices.</div>""" else u.devices.joinToString("") { d ->
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
