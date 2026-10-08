#!/usr/bin/env bash
# R338 (FR-R338-2) — no white is hard-coded in Ravilo's shared UI where it sits on a theme surface.
#
# In a light theme (Daylight) a hard-coded Color.White on the page, a sheet or a card is white on white. Every
# translucent fill, border, track and line of text on a theme surface is drawn in `colors.fg` (white in every dark
# theme, near-black in Daylight). White is right only over artwork, video, an accent or brand fill, a coloured badge
# or a fixed dark panel (the film player, the hero, the cast mini bar, the toasts) — and those are the counts below.
#
# This fails when a file draws more Color.White than it is allowed. A new white over artwork: raise that file's count
# here with the reason in the commit. A new white on a theme surface: use RaviloTheme.colors.fg instead.
set -euo pipefail
cd "$(dirname "$0")/.."

ROOT=ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui
# file (under $ROOT)                            allowed
ALLOWED="
  components/AppBar.kt                          1
  components/Cast.kt                            9
  components/CertBadge.kt                       4
  components/ChannelCard.kt                     2
  components/DesktopNav.kt                      4
  components/EpisodeCard.kt                     4
  components/HeroCarousel.kt                    1
  components/MultiEpisodeCard.kt                3
  components/RaviloBottomNav.kt                 1
  components/ServerMessageHost.kt               1
  components/Tile.kt                            7
  music/BookPlayerScreens.kt                    2
  music/DesktopMusicBar.kt                      1
  music/MusicCommon.kt                          4
  music/MusicDetailScreens.kt                   3
  music/MusicPlayerScreens.kt                   3
  screens/CastRemoteScreen.kt                   2
  screens/HomeScreen.kt                         1
  screens/LiveTvPlayerScreen.kt                 1
  screens/PlayerHandsetChrome.kt                33
  screens/PlayerIdent.kt                        1
  screens/PlayerScreen.kt                       74
  screens/ProfileScreen.kt                      1
  screens/SettingsScreen.kt                     4
  screens/UpcomingScreen.kt                     5
  theme/Colors.kt                               1
"

fail=0
while IFS=: read -r path count; do
  rel=${path#$ROOT/}
  allowed=$(printf '%s\n' "$ALLOWED" | awk -v f="$rel" '$1 == f { print $2 }')
  allowed=${allowed:-0}
  if [ "$count" -gt "$allowed" ]; then
    echo "MORE WHITE  $rel: $count Color.White (allowed $allowed)"
    fail=1
  fi
done < <(grep -rc "Color.White" "$ROOT" --include=*.kt | grep -v ':0$')

if [ "$fail" -eq 0 ]; then
  echo "OK — no new hard-coded white in Ravilo's shared UI."
else
  echo
  echo "On a theme surface, draw it in RaviloTheme.colors.fg (white in a dark theme, ink in Daylight)."
  echo "Over artwork, video or a coloured fill, raise the file's count in this script."
fi
exit "$fail"
