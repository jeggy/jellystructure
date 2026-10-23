#!/usr/bin/env bash
# Regression fence for hand-authored CSS that a design-sync silently strips.
#
# design/app/*.css is exported wholesale by the separate design-mockup project on every "updated
# designs" sync (see CLAUDE.md "Watch the loop") — a rule added here, in the repo, with no matching
# source in that project gets clobbered on the next export with no warning.
#
# This has now happened THIRTEEN times (2026-07-13, 07-31, 08-02, 08-07, 08-10 ×2, 08-11, 08-13,
# 08-28, 09-02, 09-05, 09-12, 09-16), wiping the same blocks over and over. Prose warnings did not
# stop it: the CLAUDE.md warning added after the 3rd incident was followed by five more. This script
# is the technical fence instead — it was created after the 2nd incident (Phase 138) but only ever
# tracked ONE rule, so it kept passing while everything else was being wiped. It now covers every
# block a sync has actually destroyed.
#
# 13th incident (2026-09-16) also deleted the 3 font .woff2 binaries AGAIN (see the font-existence
# check below — wf.css referencing the right URL isn't enough if the file itself is gone) and, for
# the first time, reintroduced REAL de-anonymized infrastructure values (real IPs, a real domain, a
# real private-tracker name + its announce passkey) across ~20 spec files and design/app/{metadata,
# library,seeding}.js — see scripts/check-deanonymization.sh, a separate fence for that class.
#
# The 9th incident (08-28) also hit two files this script didn't cover at all: it reverted the
# 2026-08-13 .sx-scoping fix in segments.css wholesale (caught by check-css-scoping.sh, run alongside
# this script) AND quietly dropped ravilo-player.js's R208 episode-rail auto-hide, which no fence
# caught since that file isn't CSS. Both are now tracked below.
#
# ADD A LINE HERE whenever a design-sync-fragile CSS fix lands in this repo. That is the whole point of
# this file; a fix that isn't listed here is one sync away from silently disappearing again.
set -euo pipefail
cd "$(dirname "$0")/.."

fail=0

# check <file> <description> <literal-substring-that-must-be-present>
check() {
  local file="$1" desc="$2" pattern="$3"
  if ! grep -qF "$pattern" "$file"; then
    echo "MISSING      $desc"
    echo "             (not found in $file: $pattern)"
    fail=1
  fi
}

APP="design/app/app.css"
DETAIL="design/app/detail.css"
WF="design/app/wf.css"

# ---- app.css -------------------------------------------------------------------------------------
# Phase 138 — "Recently processed" + "Quick actions" are desktop-only on the Dashboard.
check "$APP" "dashboard mobile-hide (.dash-sidecol)" \
  ".dash-sidecol { display: none; }"
# minmax(0, 1fr), not bare 1fr: without it any unwrapped long string (a series title, a raw filename)
# becomes the main column's automatic minimum and blows the whole page wider than the viewport.
check "$APP" "shell grid width fix, desktop (.shell minmax)" \
  "grid-template-columns: 248px minmax(0, 1fr)"
check "$APP" "shell grid width fix, mobile (.shell minmax)" \
  ".shell { grid-template-columns: minmax(0, 1fr); }"
# Phase 148 — collapsible sidebar nav groups (.group-label doubles as a <button>).
check "$APP" "sidebar collapsible nav groups (.nav-group)" \
  ".app-side .nav-group { display: flex; flex-direction: column; }"
check "$APP" "sidebar nav group toggle (.group-toggle)" \
  ".app-side .group-label.group-toggle"
check "$APP" "sidebar nav group chevron (.grp-chev)" \
  ".app-side .nav-group.collapsed .grp-chev"
# DEB-1492 (2026-10-16) — the AI page's shared chrome, promoted from round 5 by hand: the tab strip came
# from the design project's support.css and the lost-connection strip from ai.html's inline <style>, and
# NEITHER of those two files is served, so app.css is the only copy that ships. The design project's own
# app.css has no idea these rules exist.
check "$APP" "AI page tab strip (.tk-tabs)" ".tk-tabs { display:flex; gap:4px;"
check "$APP" "AI page lost-connection strip, FR-7 (.ai-offl)" ".ai-offl { display:none; align-items:flex-start;"
check "$APP" "AI page lost-connection strip is revealed by main.ws-lost" "main.ws-lost .ai-offl { display:flex; }"
check "$APP" "AI page lost-connection disables Download/No thanks/Rebuild, FR-7" \
  "main.ws-lost #ai-suggestions :is([data-dl],[data-no],#sg-rebuild)"

# ---- detail.css ----------------------------------------------------------------------------------
# Phase 44 — the NFO raw viewer (tree + pane) on media/series detail.
check "$DETAIL" "NFO raw viewer layout (.nfo-layout)" ".nfo-layout {"
check "$DETAIL" "NFO raw viewer tree (.nfo-tree)"     ".nfo-tree {"
check "$DETAIL" "NFO raw viewer caret (.nfo-caret)"   ".nfo-caret {"
# DEB-2051 (FR-10) — the AVI→MKV notice's hand-authored rules, promoted by DEB-2137: a later copy over detail.css must
# not lose them silently.
check "$DETAIL" "container-conversion notice (.cv-card)"          ".cv-card {"
check "$DETAIL" "container-conversion scoped focus ring"          ".cv-card .btn:focus-visible, .cv-dlg .btn:focus-visible"
check "$DETAIL" "container-conversion light-theme tints"          "[data-theme=\"light\"] .cv-keep"
check "$DETAIL" "track row restacks at phone width (FR-9 narrow)" ".trk-main > :nth-child(2), .trk-main > :nth-child(3)"

# ---- wf.css --------------------------------------------------------------------------------------
# Phase 117/146 — dashboard "Needs your attention" breakdown grid.
check "$WF" "dashboard attention breakdown (.attn-breakdown)" ".attn-breakdown {"
check "$WF" "dashboard attention breakdown item (.abk)"       ".abk {"
# Selected-filter-chip state — Library's .active-chip and Activity's .act.
check "$WF" "selected filter chip (.active-chip / .act)" ".chip.active-chip, .chip.act"
# Activity — worker dots + job rows + the job-queue block.
check "$WF" "activity worker dot (.wk-dot)" ".wk-dot {"
check "$WF" "activity job row (.jobrow)"    ".jobrow {"
check "$WF" "activity job-queue block (.jq-*)" ".jq-"
# Dashboard/Library hero thumbnail.
check "$WF" "hero thumbnail (.hero-thumb)" ".hero-thumb {"
# Settings — age-rating cascade editor (Phase 155).
check "$WF" "age-rating cascade list (.rc-list)" ".rc-list {"
check "$WF" "age-rating cascade item (.rc-item)" ".rc-item {"
# Error/alert banner.
check "$WF" "alert banner (.alert-bad)" ".alert-bad {"
# FR-167-3 — self-hosted fonts (design/app/fonts/*.woff2), replacing the Google Fonts @import the CSP
# was silently blocking. A design sync reverting this to the @import breaks admin typography again with
# no visible error, so it's fenced the same as everything else here.
check "$WF" "self-hosted fonts, not the Google Fonts @import" "url('fonts/jetbrains-mono.woff2')"
# wf.css referencing the right URL doesn't help if the binary itself is gone — a sync has deleted
# these three outright (not just the CSS rule) at least twice (9th, 10th, 12th, 13th incidents).
for font in jetbrains-mono sora space-grotesk; do
  f="design/app/fonts/$font.woff2"
  if [ ! -s "$f" ]; then
    echo "MISSING      self-hosted font file ($f)"
    fail=1
  fi
done

# R239 — every flag rule in flags.css must have its SVG on disk. The 13th AND 14th incidents both
# deleted design/flags/4x3/ct.svg (Catalan, hand-authored, a deliberately non-ISO code) while leaving
# flags.css's own .fi-ct rule alone in one of them — so neither a selector check nor check-lang-cc-sync
# (which compares LANG_CC key counts, not assets) noticed. A missing asset renders an empty box, not an
# error. Checked generically so any future flag loss trips this too, not just Catalan.
missing_flags=""
while IFS= read -r code; do
  [ -s "design/flags/4x3/$code.svg" ] || missing_flags="$missing_flags $code"
done < <(grep -oE '^\.fi-[a-z]{2,3}' design/flags.css | sed 's/^\.fi-//' | sort -u)
if [ -n "$missing_flags" ]; then
  echo "MISSING      flag asset(s) for flags.css rule(s):$missing_flags  (design/flags/4x3/<code>.svg)"
  fail=1
fi
# 15th incident (2026-09-25) removed BOTH the .fi-ct rule and ct.svg in one export, so the generic
# rule→asset check above passed vacuously. The Catalan flag exists only in this repo (the design tool
# ships lipis's 99), so it is asserted positively: the rule and the file, each on its own.
check "design/flags.css" "Catalan flag rule .fi-ct (R239 amendment; repo-only, not in the design tool)" \
  '.fi-ct { background-image: url("flags/4x3/ct.svg"); }'
if [ ! -s "design/flags/4x3/ct.svg" ]; then
  echo "MISSING      design/flags/4x3/ct.svg (Catalan flag, hand-authored — restore from git, it is not in the design tool)"
  fail=1
fi

# ---- segments.css / ravilo-player.js --------------------------------------------------------------
SEGCSS="design/app/segments.css"
PLAYER="design/ravilo/ravilo-player.js"

# 2026-08-13 — .sx must actually BE the viewport-bounded flex column its children's flex:1/min-height:0
# assume, or the trim view's <video style="height:100%"> falls back to its intrinsic size and balloons
# to fill the whole screen. Scoping-only checks (check-css-scoping.sh) don't catch this since .sx's own
# rule body losing content doesn't change its selector.
check "$SEGCSS" "trim-view viewport-bounded flex column (.sx height:100dvh)" \
  "height:100vh;height:100dvh;display:flex;flex-direction:column;overflow:hidden;font-family:'Sora'"
# R208 (ex-R196) — episode rail auto-closes after 30s of inactivity.
check "$PLAYER" "episode-rail 30s auto-hide (EPRAIL_HIDE_MS)" "EPRAIL_HIDE_MS = 30000"

# 2026-09-05 (11th incident) — R222 FR-R222-5: the "slow to start" note rides EPISODE rows, since a
# decode ceiling is per device and a bitrate is per file, so a series hero cannot speak for episodes
# that are different files. The sync kept only the movie-detail half (FR-R222-4) and dropped the
# episode half wholesale on TV and phone. FR-R222-5 is ✅ Built in the Compose app, so the mockups
# silently disagreeing with shipped code is the regression.
RAVCSS="design/ravilo/ravilo.css"
RAVAPP="design/ravilo/ravilo-app.js"
RAVDATA="design/ravilo/ravilo-data.js"
RAVMOB="design/ravilo/Ravilo Mobile.html"
check "$RAVCSS"  "R222 episode-row note, TV (.ep-playnote)"        ".ep-playnote {"
check "$RAVAPP"  "R222 episode-row note render, TV (epNoteHTML)"   "function epNoteHTML(item, season, ep)"
check "$RAVAPP"  "R222 series hero never carries the note"         "if (item.kind === 'series') return '';"
check "$RAVDATA" "R222 per-episode note keys (title|SxxEyy — S01E05 form since 2026-09-28)" "'Nordvest|S01E08'"
check "$RAVDATA" "R222 note-key builder pads to S01E05 form (the 2026-09-28 sync renamed the keys but not the builder)" "String((season || 0) + 1).padStart(2, '0') + 'E' + String(ep.n).padStart(2, '0')"
check "$RAVDATA" "R222 note lookup takes season+episode"           "function playbackNoteFor(item, season, ep)"
check "$RAVMOB"  "R222 episode-row note, phone (.meslow)"          ".meslow{"
check "$RAVMOB"  "R222 episode-row note render, phone"             "function epSlowHTML(it, ep)"

# 2026-09-05 — Users & devices (Phase 143/185/187): .usr-cap/.usr-av never made it into a served
# stylesheet at all until now (found while building 187's admin photo chip, on the same page).
check "$WF" "Users & devices: decode-ceiling line (.usr-cap)" ".usr-cap {"
check "$WF" "Users & devices: read-only photo chip (.usr-av)" ".usr-av {"
# Phase 259 — the per-device version history's timeline lives in the served wf.css, not only in the
# mockup's inline <style>; a sync that strips it leaves the toggle opening an unstyled block.
check "$WF" "Users & devices: version history (.usr-vh)" ".usr-vh {"
check "$WF" "Users & devices: version history rows (.vh-r)" ".vh-r {"
# Phase 256 — the unstable-connection and releases-behind lines on a device row (server-decided).
check "$WF" "Users & devices: unstable-connection line (.usr-cap.unstable)" ".usr-cap.unstable"
check "$WF" "Users & devices: releases-behind line (.usr-cap.behind)" ".usr-cap.behind"
# Phase 304 (R368) — Users & devices ▸ *Playing now*: the session table was hand-ported into the served
# wf.css from ravilo-users.html and amended 2026-10-12 (FR-304-8/9). The first full mirror of the design
# project (DEB-792, 2026-10-12) found the studio's wf.css is this repo's minus exactly this block, and
# nothing in the repo would have noticed it going missing — so it is fenced here.
check "$WF" "Playing now: session rows (.ses-tbl)" ".ses-tbl td { vertical-align: middle; }"
check "$WF" "Playing now: what-is-playing cell (.ses-what)" ".ses-what {"
check "$WF" "Playing now: progress bar (.ses-pg)" ".ses-pg {"
check "$WF" "Playing now: *Ended today* fold, FR-304-8 (.ses-fold)" ".ses-tbl tr.usr-hd.ses-fold td { cursor: pointer;"
check "$WF" "Playing now: expanded-row controls (.ses-ctl)" ".ses-ctl {"
check "$WF" "Playing now: per-session picker (.ses-sel)" ".ses-sel {"
# Phase 260 — the empty-queues panel on Activity ▸ Jobs & workers lives in the served wf.css.
check "$WF" "Jobs & workers: empty-queues panel (.qe)" ".qe {"
check "$WF" "Jobs & workers: empty-queues option row (.qe-opt)" ".qe-opt {"
# Phase 218 — the Chromecast card's page-local rules live in the served wf.css, not only in the
# design's inline <style>; a sync that strips them leaves the Settings card unstyled.
check "$WF" "Chromecast card steps (.cc-step)" \
  ".cc-step { display: grid; grid-template-columns: 28px 1fr;"
check "$WF" "Chromecast card status dots (.cc-dot)" \
  ".cc-dot { width: 8px; height: 8px; border-radius: 50%;"
# Phase 314 (FR-314-17) — a skipped library's card dims only what `skip` governs and leaves its two
# path fields editable; without these rules the dim disappears and a disabled field looks typeable.
check "$WF" "Skipped library card: only the governed rows dim (.lib-skip-gov)" \
  ".lib-card.skipped .lib-skip-gov { opacity: .5; }"

# Phase 223 — the segment editor's drag engine keeps its rules in the served segments.css; a sync that
# drops them leaves touch users with 11 px handles, no bubble and an inert zoom strip.
check "$SEGCSS" "Phase 223 coarse-pointer hit areas" "@media (pointer:coarse){.sx .track{height:56px}"
check "$SEGCSS" "Phase 223 drag timecode bubble (.dragtip)" ".sx .dragtip{"
check "$SEGCSS" "Phase 223 zoom-strip overlay bar (.zseg)" ".sx .wave .zseg{"
check "$SEGCSS" "Phase 223 bars no longer clip their handles" "cursor:grab;overflow:visible;white-space:nowrap}"

# 2026-09-17 — season sheet: `.stat` is ALSO app.css's dashboard stat card, whose overflow:hidden let
# .sxstage squash the strip to a clipped sliver; and the drawer's thumbnail inherited the trim view's
# min-height:230px, which the 16/9 aspect ratio turned into a 409px min-WIDTH spilling over the text.
# The mockup loads no app.css and so never shows either; only the served page does.
check "$SEGCSS" "season sheet stats strip resets app.css's .stat card" \
  ".sx .stat{display:flex;gap:10px;flex-wrap:wrap;flex:none;padding:0;border:0;"
check "$SEGCSS" "drawer thumbnail stays in its 250px column" \
  ".sx .drawer .vid{flex:none;aspect-ratio:16/9;min-height:0;width:100%;align-self:start}"
check "$SEGCSS" "season sheet State column wide enough for one line" \
  "grid-template-columns:26px 62px 186px 1fr 196px 128px"

# Phase 287 (FR-287-4) — the split preview's rules live inline in the design's audiobook.html; the served page loads
# only the synced stylesheets, so music.css carries a copy the next export will not have.
MUSICCSS="design/app/music.css"
check "$MUSICCSS" "audiobook split preview columns (.ab-scols)" \
  ".ab-scols{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}"
check "$MUSICCSS" "audiobook split preview part row (.ab-spart)" \
  ".ab-spart{display:grid;grid-template-columns:18px 26px minmax(0,1fr) auto;"
check "$MUSICCSS" "audiobook split preview empty column (.ab-sempty)" \
  ".ab-sempty{padding:26px 8px;text-align:center;"

# R332 (2026-09-29) — a finished audiobook can be marked not finished. Made repo-side in the phone mockup and its
# string table; the 2026-09-30 export (18th incident) dropped both, the strings under R340's new ones.
check "design/ravilo/mobile/ravilo-books.js" "R332 book sheet: Mark as not finished row" \
  "row('unfinish', t('ab.mark_unfinished'))"
check "design/ravilo/ravilo-i18n.js" "R332 string ab.mark_unfinished (en)" "'ab.mark_unfinished': 'Mark as not finished'"
check "design/ravilo/ravilo-i18n.js" "R332 string ab.mark_unfinished (da)" "'ab.mark_unfinished': 'Markér som ikke færdig'"
check "design/ravilo/ravilo-i18n.js" "R332 string ab.mark_unfinished (fo)" "'ab.mark_unfinished': 'Merk sum ikki liðugt'"

# Phase 292 (dev review 16, 17, 2026-10-01) — versions.css is served; made repo-side: the chip is 11 px, and its text is
# 45 % colour on a light theme (58 % fails 4.5 : 1). The design tool's copy has neither.
VERCSS="design/app/versions.css"
check "$VERCSS" "292 version chip 11 px" ".vr-b{display:inline-flex;align-items:center;font-size:11px;"
check "$VERCSS" "292 version chip 45 % on light" '[data-theme="light"] .vr-b{color:color-mix(in oklch,var(--c) 45%,var(--ink))}'
check "$VERCSS" "292 +N chip 11 px" ".vr-more{display:inline-flex;align-items:center;font-size:11px;"

# DEB-1492 (2026-10-16) — the Suggestions page hosted as the AI page's third tab, promoted from round 5.
SUGCSS="design/app/suggestions.css"
check "$SUGCSS" "in-tab toolbar (.sg-tb)" ".sg-tb{display:flex;align-items:center;gap:8px 12px;"
check "$SUGCSS" "in-tab Dismissed view replaces the list, FR-6.2" \
  '[data-sg-host="tab"].sg-dview>:is(.page-sub,#sg-who,#sg-grp,#sg-main){display:none}'
check "$SUGCSS" "Dismissed table stacks at <=560px INSIDE THE TAB ONLY, FR-6.2" \
  '@media (max-width:560px){[data-sg-host="tab"] .sg-dtable thead{display:none}'
# FR-274-18 (7e2a2283, DEB-1368) — the Download dialog is bounded by its FLEX PARENT, not the viewport.
# `calc(100vh - 60px)` lets a phone's browser toolbar clip the primary button; the design project's copy
# of suggestions.css predates that fix and still carries the calc(), so round 5 offered it back as if it
# were new work and DEB-1492 refused it at the gate. Fenced so the next round cannot land it quietly.
check "$SUGCSS" "Download dialog height is max-height:100%, NOT calc(100vh - 60px)" \
  ".sg-dl{width:min(520px,100%);max-height:100%;"
if grep -qF "max-height:calc(100vh - 60px)" "$SUGCSS"; then
  echo "REVERTED     .sg-dl max-height is back to calc(100vh - 60px) — that is FR-274-18 undone"
  echo "             (in $SUGCSS; restore max-height:100%, see 7e2a2283 / DEB-1368)"
  fail=1
fi

# DEB-1503 (2026-10-16, FR-17) — the admin's Support surface, promoted from round 3 (support-lane) into a
# new page-scoped file plus two rules in app.css. Everything listed here was authored BY HAND during the
# promotion: the design project's own support.css has none of it, so a future round offering that file
# back would be a straight regression of all of it.
SUPCSS="design/app/support.css"
check "$SUPCSS" "Support is scoped under .tksup, not under the bare .tk- prefix" \
  ".tksup .tk-row { display:grid;"
check "$SUPCSS" "FR-3 lane strip, closed state (.tk-lane.closed)" \
  ".tksup .tk-lane.closed { background:var(--warn-soft);"
check "$SUPCSS" "FR-8 admin-only tag, and its unfilled bubble (.tk-bdg.adm)" \
  ".tksup .tk-m.adm .t { background:transparent; border:1px dashed var(--line-2);"
check "$SUPCSS" "FR-6/FR-10.4 overdue badge is warn, never bad (.tk-od)" \
  ".tksup .tk-od { display:inline-flex;"
check "$SUPCSS" "FR-10.9 transcript panel rides the shared dialog (.modal.tk-tr)" \
  ".tksup .modal.tk-tr { width:760px; }"
# FIX 1 — the holder slot had its own unstyled block overlapping the title in round 3's frames.
check "$SUPCSS" "thread header is a 2-column grid so the holder cannot overlap the title" \
  ".tksup .tk-dh { display:grid; grid-template-columns:minmax(0,1fr) auto;"
check "$SUPCSS" "holder chip sits in the header's second column" \
  ".tksup .tk-dh .tk-hold { grid-column:2; grid-row:1 / span 2;"
# FIX 2 — FR-8: Show the rest sits INSIDE the clamped bubble, and the clamp is by line, not by height.
check "$SUPCSS" "clamped bubble clamps 14 LINES (never mid-glyph)" \
  "display:-webkit-box; -webkit-box-orient:vertical; -webkit-line-clamp:14;"
check "$SUPCSS" "Show the rest is a child of the bubble (.tk-m .t .more)" \
  ".tksup .tk-m .t .more { align-self:flex-start;"
# FIX 3 — the log's action column was squeezed to the right edge and wrapped three lines deep.
check "$SUPCSS" "log row has no fixed columns and #n cannot wrap (.tk-lg)" \
  ".tksup .tk-lg { display:grid; grid-template-columns:max-content max-content minmax(0,1fr) max-content;"
check "$SUPCSS" "FR-10 rail moves under the thread, unstuck, at <=900px" \
  "@media (max-width:900px) {"
check "$SUPCSS" "FR-6 list stacks at <=560px" \
  ".tksup .tk-row { display:flex; flex-wrap:wrap;"
# app.css's half of the same promotion.
check "$APP" "AI tab strip count badge (.tk-cnt)" ".tk-cnt { display:inline-flex; align-items:center;"
check "$APP" "FR-16 lost connection disables every Support write" \
  "main.ws-lost #ai-support :is([data-tk-w],.tk-cp textarea)"

# DEB-2193 (2026-10-23) — the admin's PROCESSING surfaces, promoted by hand from design round
# `processing-features` revision 1 for processing-runs-as-nine-features.md. Every rule in this file
# was authored during the promotion: the round's own stylesheet is a mockup that links wf.css and
# styles a canvas, so a future copy of ANY file over this one is a straight regression of all of it.
# Nine of the rules below exist only because a rendered picture showed the defect they fix — the
# fences cannot see any of those, which is exactly why they are pinned here.
PROCCSS="design/app/processing.css"
check "$PROCCSS" "Processing is scoped under .pfx, not under the bare .pf- prefix" \
  ".pfx .pf-head { display: grid; grid-template-columns: 44px minmax(0, 1.25fr) minmax(0, 1fr) auto 26px;"
check "$PROCCSS" "a feature that is off is dimmed and explained, never hidden (.pf-row.off)" \
  ".pfx .pf-row.off .pf-name"
check "$PROCCSS" "the interval LABEL is uppercased, not the shipped .select beside it" \
  ".pfx .pf-age > span:first-child"
check "$PROCCSS" "the queues-work note is the info rail (.pf-note.q)" \
  ".pfx .pf-note.q { border-left: 3px solid var(--info); }"
check "$PROCCSS" "the Scan dialog's scrim is a real fixed overlay, revealed by .open" \
  ".pfx.pf-scrim { position: fixed; inset: 0; z-index: 120;"
check "$PROCCSS" "the dialog is bounded by the viewport and its panes scroll, not the page" \
  ".pfx .pf-dlg { width: 760px; max-width: 100%; max-height: 100%;"
check "$PROCCSS" "the owner's two panes (.sd-two 292px + 1fr)" \
  ".pfx .sd-two { display: grid; grid-template-columns: 292px minmax(0, 1fr);"
check "$PROCCSS" "the left row keeps its own tick, count and marker on a second line" \
  ".pfx .sd-two .pick { display: grid; grid-template-columns: 19px minmax(0, 1fr) 10px;"
check "$PROCCSS" "the drill-in chevron appears in the one-pane face only (.sd-two.one .go)" \
  ".pfx .sd-two.one .pick .go { display: block; }"
check "$PROCCSS" "step two does not print the feature name twice (.sd-one-pane h3)" \
  ".pfx .sd-one-pane .sd-pane-head h3 { display: none; }"
check "$PROCCSS" "a feature off in Settings is inert in the RIGHT pane too (.sd-parts.off)" \
  ".pfx .sd-parts.off { opacity: .55; }"
check "$PROCCSS" "the queues-work legend sits once, under both panes (.sd-legend)" \
  ".pfx .sd-legend { padding: 14px 20px 0; flex: none; }"
check "$PROCCSS" "a partly-ticked feature's own tick state (.tick.part)" \
  ".pfx .tick.part { background: var(--hi-soft); border-color: var(--hi); color: var(--acc-ink); font-weight: 700; }"
check "$PROCCSS" "the full-rescan switch is warn-tinted when on (.pf-all.on)" \
  ".pfx .pf-all.on { border-color: var(--warn); background: var(--warn-soft); }"
check "$PROCCSS" "Queued work overrides wf.css's 8 px .dot inside its own row only" \
  ".pfx .lane .dot { width: 10px; height: 10px; box-shadow: none;"
check "$PROCCSS" "the Queued work list is ruled off from the card's lede (.pf-queued)" \
  ".pfx .pf-queued { border-top: 1px solid var(--line); margin-top: 10px; }"
check "$PROCCSS" "in the Schedule table the popover opens BESIDE the button, not over the next row" \
  ".pfx .sched .pf-pop { top: -7px; right: calc(100% + 11px); width: 258px; }"
check "$PROCCSS" "a title's own checks indent under their feature (.chk-sub)" \
  ".pfx .chk-sub { padding-left: 18px; border-left: 2px solid var(--line); margin-left: 4px; }"
check "$PROCCSS" "\`can't tell\` is a neutral badge, never warn or bad (.badge.flat)" \
  ".pfx .badge.flat { background: var(--fill-3);"
check "$PROCCSS" "a control that can do nothing new is shown and disabled (.btn.dis)" \
  ".pfx .btn.dis { opacity: .5; border-style: dashed; }"
check "$PROCCSS" "the one switch that cannot be turned off is shown and disabled (.toggle.dis)" \
  ".pfx .toggle.dis { opacity: .55; border-style: dashed; }"
check "$PROCCSS" "a Refresh ▾ item carries a wrapping sentence, so the shipped nowrap is undone" \
  "align-items: center; font-size: .88rem; white-space: normal; }"
check "$PROCCSS" "the toast's queued and cancelled variants (.pf-toast.warn)" \
  ".pfx .pf-toast.warn { border-left-color: var(--warn); }"
check "$PROCCSS" "the loading skeleton row (.sk-row)" \
  ".pfx .sk-row { display: grid;"
# The light-theme twins. --ok/--warn/--bad/--info are not re-tinted per theme, so each of these is
# the difference between the shipped colour and a pale mint or amber on white.
check "$PROCCSS" "light theme: a failing check's reason" \
  '[data-theme="light"] .pfx .chk-row .why.bad { color: #c23b2d; }'
check "$PROCCSS" "light theme: the socket-is-gone banner" \
  '[data-theme="light"] .pfx .pf-offline { color: #9a6a06; }'
check "$PROCCSS" "light theme: the queues-work marker in the left pane" \
  '[data-theme="light"] .pfx .sd-two .pick .c .q { color: #1f7fb8; }'
check "$PROCCSS" "light theme: a toast's own icon" \
  '[data-theme="light"] .pfx .pf-toast .ic { color: #128a5f; }'
# The narrow faces, at the house breakpoint. Four of the five were found by rendering at 412 px.
check "$PROCCSS" "the narrow block is at the house breakpoint" "@media (max-width: 640px) {"
check "$PROCCSS" "the feature row stacks instead of keeping five columns" \
  ".pfx .pf-when { flex-basis: 100%; order: 9; padding-left: 56px;"
check "$PROCCSS" "Queued work's controls drop to their own line" \
  ".pfx .lane .acts { grid-column: 2; margin-top: 10px; }"
check "$PROCCSS" "a long chip does not split the feature name on a Schedule card" \
  ".pfx .sched-card .r1 .f { flex-basis: 100%; }"
check "$PROCCSS" "the Checks row folds last-ran and next-due under the name" \
  ".pfx .chk-row { grid-template-columns: minmax(0, 1fr) 34px; }"
check "$PROCCSS" "…and reveals the folded line, which is not a grid child" \
  ".pfx .when-ph { display: block; }"
# Three things that must never come back into this file, each one a decision rather than a tidy-up.
# Read with the comments stripped: this file's own header explains both of the first two by name, and
# a guard that trips on the explanation of why a rule is absent is a guard nobody can keep green.
PROCCODE=$(perl -0777 -pe 's{/\*.*?\*/}{}gs' "$PROCCSS")
if printf '%s' "$PROCCODE" | grep -qF "Cancel starts nothing"; then
  echo "REVERTED     the Scan dialog's foot note is back in $PROCCSS"
  echo "             (\"Cancel starts nothing\" is a rule the Cancel button must OBEY, not copy the"
  echo "              dialog carries — dropped as correction 4 of the round)"
  fail=1
fi
if printf '%s' "$PROCCODE" | grep -qF -- "--blur: none"; then
  echo "REVERTED     $PROCCSS carries --blur: none"
  echo "             (that is the round's CANVAS substitution for the screenshot tool; the live admin"
  echo "              keeps the glass wf.css gives every .card)"
  fail=1
fi
if printf '%s' "$PROCCODE" | grep -qE "https?:"; then
  echo "REVERTED     $PROCCSS carries a network reference"
  echo "             (a served stylesheet never gains one — hosted fonts are a mockup thing only)"
  fail=1
fi

if [ "$fail" -eq 0 ]; then
  echo "OK — every design-sync-fragile CSS rule tracked here is present."
else
  echo
  echo "A design sync has almost certainly clobbered hand-authored CSS again."
  echo "Fix: git diff <pre-sync-ref> <sync-ref> -- design/app/{app,detail,wf}.css to confirm it is pure"
  echo "regression, then restore with:  git diff <sync-ref> <pre-sync-ref> -- design/app/*.css | git apply"
fi
exit "$fail"
