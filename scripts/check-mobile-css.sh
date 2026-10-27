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

# DEB-2628 (FR-12, promoted 2026-10-27 from round `radio`/DEB-2640) — Live TV's lineup marks a radio
# station, and a hidden row still badged `new` is NOT faded. Four hand-authored rules, none of which
# exists in any mockup: the Live TV view's own CSS is injected from LiveTv.kt at runtime, so these
# cannot live there without being retyped in Kotlin, and `.radiomark` is shared with the filter chip.
# The `tr.off.new` rule is the one most at risk: it is an override of a page-local rule, so deleting
# it looks like removing a redundant line and silently restores the 50 % dim on brand-new stations.
check "$WF" "radio mark: the inline wrapper (.radiomark)" \
  ".radiomark { display: inline-flex; align-items: center; vertical-align: -2px;"
check "$WF" "radio mark: the glyph's stroke treatment (.radiomark svg)" \
  "fill: none; stroke: currentColor; stroke-width: 2.1; stroke-linecap: round;"
check "$WF" "radio mark: takes the chip's own ink and gap in the filter row (.chip .radiomark)" \
  ".chip .radiomark { margin-right: 0; color: inherit; }"
check "$WF" "lineup: a hidden row still badged new is drawn at full strength (FR-12)" \
  ".lineup tr.off.new td { opacity: 1; }"

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

# ---- ai.css --------------------------------------------------------------------------------------
# The AI page's Auto-fetch surfaces. Round 2 (`admin-ai`) drew the page; round `auto-fetch-states`
# revision 2 drew the states it did not, and the promotion of 2026-10-25 (DEB-2484, FR-14.3) turned
# the mockup file into the production one by hand; round `auto-fetch-retention` added the page-only
# fetcher and Keep on 2026-10-27 (DEB-2641). Every rule below is hand-authored, and many of them are
# fixes found only by opening a render, which no fence can see for itself.
AICSS="design/app/ai.css"
check "$AICSS" "Auto-fetch is scoped under .afx, not under the bare .af-/.tq- prefixes" \
  ".afx .af-form{display:grid;grid-template-columns:minmax(0,1fr) 220px auto;"
check "$AICSS" "a control that cannot do anything new is shown and disabled (.af-dis)" \
  ".afx .af-dis{opacity:.5;border-style:dashed;pointer-events:none}"
check "$AICSS" "…and the one switch that cannot be flipped reads as disabled too (.toggle.af-dis)" \
  ".afx .toggle.af-dis{opacity:.55}"
check "$AICSS" "one status slot per row: the badge says the state, the line under it where and when" \
  ".afx .af-stat{display:flex;flex-direction:column;gap:3px;align-items:flex-start}"
check "$AICSS" "the stopped row's tint, on the row and not on the Last run cell" \
  ".afx .af-tbl tr.af-stopped td{background:color-mix(in oklch,var(--bad) 6%,transparent)}"
# Found by opening the first render: the icon's row span ran out and the band's next child was
# placed in column 1, where Resume site drew half off the frame's left edge.
check "$AICSS" "every child of the Site stopped band but its icon sits in column 2" \
  ".afx .af-band > :not(.bi){grid-column:2}"
check "$AICSS" "the band's actions are .af-acts, so this file's own .af-tbl .acts cannot centre them" \
  ".afx .af-band .af-acts{display:flex;"
check "$AICSS" "the stop is never retried, so the band carries a no-timer line instead of a countdown" \
  ".afx .af-band .no-timer{"
check "$AICSS" "the four stop causes in one block (.af-causes)" \
  ".afx .af-causes{display:grid;grid-template-columns:150px minmax(0,1fr);"
check "$AICSS" "a waiting item's two number fields are one flex row, not a 1fr column and a 96 px one" \
  ".afx .af-item .ctl{display:flex;align-items:flex-end;"
check "$AICSS" "the proposed number reads as a proposal, not as a stored value (.af-prop)" \
  ".afx .af-prop{margin-top:10px;"
check "$AICSS" "the proof chip on the review state (.af-proof .mk)" \
  ".afx .af-proof .mk{display:inline-flex;"
check "$AICSS" "…and what an agreement is, said in the line under it (.af-proof .means)" \
  ".afx .af-proof .means{display:block;"
check "$AICSS" "the disagreeing rows under a failed proof (.af-against .off)" \
  ".afx .af-against .off{color:var(--bad);font-weight:600}"
check "$AICSS" "accept ticks on a mapped review (.af-tick.on)" \
  ".afx .af-tick.on{border-color:var(--hi);background:var(--hi);color:#fff}"
check "$AICSS" "a run waiting for the admin's number is a neutral dot, not wf.css's glowing one" \
  ".afx .af-run .dot.wait{background:var(--ink-soft);box-shadow:none}"
# The still Investigating marker, option `mark` — the owner's pick on 2026-10-25 (DEB-2481). It says
# running in shape, fill and label and keeps the step's number; nothing here animates.
check "$AICSS" "the four step marks are outlined circles, not round 2's filled squares" \
  ".afx .af-step .ic{width:22px;height:22px;border-radius:50%;"
check "$AICSS" "the running step is a filled accent SQUARE with a halo, which is option \`mark\`" \
  ".afx .af-step.run .ic{border-radius:5px;border-color:transparent;background:var(--hi);color:#fff;box-shadow:0 0 0 3px var(--hi-soft)}"
check "$AICSS" "…and its label says so in words too (.af-now)" \
  ".afx .af-now{color:var(--acc-ink);font-weight:600}"
check "$AICSS" "the done and failed marks lose the outline the waiting one keeps" \
  ".afx .af-step.done .ic{border-color:transparent;background:var(--ok-soft);color:var(--ok)}"
# The light-theme twins. --ok/--warn/--bad/--info are not re-tinted per theme, so each of these is
# the difference between the shipped colour and a pale mint or amber on white.
check "$AICSS" "light theme: the proven chip" \
  '[data-theme="light"] .afx .af-proof .mk{color:#128a5f}'
check "$AICSS" "light theme: the not-proven chip" \
  '[data-theme="light"] .afx .af-proof.unproven .mk{color:#9a6a06}'
check "$AICSS" "light theme: the confirmed-series pill, which must match the proven chip beside it" \
  '[data-theme="light"] .afx .af-conf{color:#128a5f}'
check "$AICSS" "light theme: the tick on a finished investigation step" \
  '[data-theme="light"] .afx .af-step.done .ic{color:#128a5f}'
check "$AICSS" "light theme: a waiting step is readable, not --ink-dim's 25% ink" \
  '[data-theme="light"] .afx .af-step:not(.done):not(.run):not(.fail){color:#5d6378}'
check "$AICSS" "light theme: the running step's own word" \
  '[data-theme="light"] .afx .af-now{color:#6240cf}'
check "$AICSS" "light theme: a disagreeing row's distance" \
  '[data-theme="light"] .afx .af-against .off{color:#c23b2d}'
check "$AICSS" "light theme: the resuming band's icon glyph stays white on the info fill" \
  '[data-theme="light"] .afx .af-band.hold .bi{color:#fff}'
check "$AICSS" "light theme: the proposal's reason" \
  '[data-theme="light"] .afx .af-prop .why{color:#6240cf}'
check "$AICSS" "light theme: the waiting run's neutral dot" \
  '[data-theme="light"] .afx .af-run .dot.wait{background:#5d6378}'
# The narrow faces, at the house breakpoint. Every one of these was found by rendering at 412 px.
check "$AICSS" "the narrow block is at the house breakpoint" "@media (max-width:640px){"
check "$AICSS" "the fetchers table becomes a card per fetcher instead of keeping five columns" \
  ".afx .af-tbl tr.af-row{border:1px solid var(--line-2);border-radius:var(--radius-s);background:var(--fill-2);padding:12px 13px;"
check "$AICSS" "the band and the detail share one card, so neither sits outside its padding (.af-open)" \
  ".afx .af-tbl tr.af-det .af-open{"
check "$AICSS" "the stopped tint is the row's, not each cell's — per cell it drew as stripes" \
  ".afx .af-tbl tr.af-row.af-stopped td{background:transparent}"
check "$AICSS" "the mapping table becomes a row per mapping instead of keeping six columns" \
  ".afx .af-map tr{position:relative;padding:10px 0 10px 30px;"
check "$AICSS" "…and the kept-row tint there is the row's too" \
  ".afx .af-map tr.keep td{background:transparent}"
check "$AICSS" "the key/value block stacks rather than holding two narrow columns" \
  ".afx .af-kv{grid-template-columns:minmax(0,1fr);gap:0}"
check "$AICSS" "the read-only recipe cannot widen the page (white-space: pre has no own min-width)" \
  ".afx .af-det-in > *{min-width:0}"
# Round `auto-fetch-retention`, promoted 2026-10-27 (DEB-2641, FR-10.6 and FR-16). Three of these
# correct rules this file had already shipped — each was found by opening a render, and the third
# was a live defect at 412 on the Royndin review state.
check "$AICSS" "the form TOP-aligns, so a three-line hint cannot lift the TMDB id input out of line" \
  ".afx .af-form{display:grid;grid-template-columns:minmax(0,1fr) 220px auto;gap:12px;align-items:start}"
check "$AICSS" "the open row's config row TOP-aligns, so Name and Check stay level beside Keep" \
  ".afx .af-cfg{display:flex;gap:10px;flex-wrap:wrap;align-items:flex-start;margin-top:8px}"
check "$AICSS" "…and the buttons take a line of their own there, since nothing bottom-aligns them now" \
  ".afx .af-cfg-go{display:flex;align-items:center;gap:8px;flex-wrap:wrap;flex:1 1 100%}"
check "$AICSS" "a mapping table with no tick column loses the ≤640 accept-tick gutter (.af-map.no-tick)" \
  ".afx .af-map.no-tick tr{padding-left:0}"
check "$AICSS" "Keep's number lives inside the option it belongs to (.af-keep .seg span)" \
  ".afx .af-keep .seg span{padding:6px 10px;display:inline-flex;align-items:center;gap:7px}"
check "$AICSS" "…and the unchosen option's value is dimmed at the same width, not a live field" \
  ".afx .af-keep .seg span:not(.on) .af-num{background:transparent;border-color:transparent;color:inherit;opacity:.6;pointer-events:none}"
check "$AICSS" "a tick's label is a sentence, not wf.css's small-uppercase .field label (.af-opt)" \
  ".afx .af-opt{display:flex;align-items:flex-start;gap:9px;cursor:pointer;font-size:.8rem;line-height:1.45;color:var(--ink);margin:2px 0 0;text-transform:none;letter-spacing:0;font-weight:400}"
check "$AICSS" "the delete preview IS the warning — no dialog, no toast (.af-prev)" \
  ".afx .af-prev{display:flex;align-items:flex-start;gap:9px;margin-top:9px;"
check "$AICSS" "a refused TMDB id is refused in the field, not in a banner (.input.af-err)" \
  ".afx .input.af-err{border-color:var(--bad);box-shadow:0 0 0 3px var(--bad-soft)}"
check "$AICSS" "a run and its deletion/guard lines are one record, so the border is the group's" \
  ".afx .af-runx{border-bottom:1px solid var(--line);padding-bottom:6px}"
check "$AICSS" "the per-deletion and guard lines under a run (.af-sub)" \
  ".afx .af-sub{margin-left:25px;padding:3px 0 3px 11px;border-left:2px solid var(--line-2);"
check "$AICSS" "the list row's retention note sits under the schedule, no sixth column" \
  ".afx .af-tbl .sched .keep{font-size:.72rem;color:var(--ink-soft);margin-top:2px}"
check "$AICSS" "a page-only echo is two lines, because the page answers in two (.af-echo.page)" \
  ".afx .af-echo.page{align-items:flex-start}"
check "$AICSS" "the date beside the code in Becomes is the body face, not the mono one" \
  ".afx .af-map .se .d{font-family:'Sora',system-ui,sans-serif;"
check "$AICSS" "light theme: the field refusal's text" \
  '[data-theme="light"] .afx .hint.af-err{color:#c23b2d}'
check "$AICSS" "stacked, the tick keeps the line under its own field and Investigate goes last" \
  ".afx .af-form .af-opt{grid-column:1;margin-top:0;order:1}"
check "$AICSS" "at 412 the two Keep options split the seg in half each, so the longest label fits" \
  ".afx .af-keep .seg span{flex:1 1 0;justify-content:center}"
check "$AICSS" "…and a run's own lines lose the dot column's indent there" \
  ".afx .af-sub{margin-left:0;padding-left:9px}"
# wf.css's share of the same promotion.
check "$WF" "the settled-neutral badge, needed by two page stylesheets (.badge.flat)" \
  ".badge.flat{ background: var(--fill-3); border-color: var(--line-2); color: var(--ink-soft); }"
# Three things that must never come back into ai.css, each one a decision rather than a tidy-up.
AICODE=$(perl -0777 -pe 's{/\*.*?\*/}{}gs' "$AICSS")
if printf '%s' "$AICODE" | grep -qF "af-spin"; then
  echo "REVERTED     the spinning Investigating marker is back in $AICSS"
  echo "             (an infinite animation cannot ship — it breaks the screenshot render and it is"
  echo "              motion nobody asked for; the still marker replaces it, round auto-fetch-states)"
  fail=1
fi
if printf '%s' "$AICODE" | grep -qF "main[data-state="; then
  echo "REVERTED     the mockup's state switcher is back in $AICSS"
  echo "             (it is ai.html's own preview toggle, unscoped by nature and not product; it"
  echo "              lives in that page's <style> block)"
  fail=1
fi
if printf '%s' "$AICODE" | grep -qF -- "--blur: none"; then
  echo "REVERTED     $AICSS carries --blur: none"
  echo "             (that is a round's CANVAS substitution for the screenshot tool; the live admin"
  echo "              keeps the glass wf.css gives every .card)"
  fail=1
fi
if printf '%s' "$AICODE" | grep -qE "https?:"; then
  echo "REVERTED     $AICSS carries a network reference"
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
