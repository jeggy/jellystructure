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

# ---- detail.css ----------------------------------------------------------------------------------
# Phase 44 — the NFO raw viewer (tree + pane) on media/series detail.
check "$DETAIL" "NFO raw viewer layout (.nfo-layout)" ".nfo-layout {"
check "$DETAIL" "NFO raw viewer tree (.nfo-tree)"     ".nfo-tree {"
check "$DETAIL" "NFO raw viewer caret (.nfo-caret)"   ".nfo-caret {"

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
check "$RAVDATA" "R222 per-episode note keys (title|SxEy)"         "'Nordvest|S1E8'"
check "$RAVDATA" "R222 note lookup takes season+episode"           "function playbackNoteFor(item, season, ep)"
check "$RAVMOB"  "R222 episode-row note, phone (.meslow)"          ".meslow{"
check "$RAVMOB"  "R222 episode-row note render, phone"             "function epSlowHTML(it, ep)"

# 2026-09-05 — Users & devices (Phase 143/185/187): .usr-cap/.usr-av never made it into a served
# stylesheet at all until now (found while building 187's admin photo chip, on the same page).
check "$WF" "Users & devices: decode-ceiling line (.usr-cap)" ".usr-cap {"
check "$WF" "Users & devices: read-only photo chip (.usr-av)" ".usr-av {"
# Phase 218 — the Chromecast card's page-local rules live in the served wf.css, not only in the
# design's inline <style>; a sync that strips them leaves the Settings card unstyled.
check "$WF" "Chromecast card steps (.cc-step)" \
  ".cc-step { display: grid; grid-template-columns: 28px 1fr;"
check "$WF" "Chromecast card status dots (.cc-dot)" \
  ".cc-dot { width: 8px; height: 8px; border-radius: 50%;"

if [ "$fail" -eq 0 ]; then
  echo "OK — every design-sync-fragile CSS rule tracked here is present."
else
  echo
  echo "A design sync has almost certainly clobbered hand-authored CSS again."
  echo "Fix: git diff <pre-sync-ref> <sync-ref> -- design/app/{app,detail,wf}.css to confirm it is pure"
  echo "regression, then restore with:  git diff <sync-ref> <pre-sync-ref> -- design/app/*.css | git apply"
fi
exit "$fail"
