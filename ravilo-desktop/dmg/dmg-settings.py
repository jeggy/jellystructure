# dmgbuild settings for ravilo-mac-<N>.dmg — the window the .dmg opens to (DEB-1469, FR-4; built by DEB-2122).
# Run by deploy-macos.yml on macos-15 as:
#   dmgbuild -s ravilo-desktop/dmg/dmg-settings.py -D app=<Ravilo.app> -D background=<background.png> Ravilo <out.dmg>
# `background@2x.png` beside the background is found and combined into one HiDPI TIFF by dmgbuild itself.
#
# The numbers are the drawn window's (specs/ravilo/requirements/the-mac-download-shows-open-anyway-in-pictures.md,
# amendment 2026-10-22). Finder icon locations are the ICON CENTRE, in points from the content area's top-left,
# and one image pixel is one point at 1x. scripts/check-dmg-layout.sh and verify-layout.py hold these same numbers.
import os

app = defines["app"]  # noqa: F821 — `defines` is injected by dmgbuild
app_name = os.path.basename(app)

format = "UDZO"  # same as the hdiutil call this replaced
filesystem = "HFS+"

files = [app]
symlinks = {"Applications": "/Applications"}

background = defines["background"]  # noqa: F821

# Content area 720 x 520 pt. WindowBounds is the window frame, title bar included, so the window is 28pt taller
# than the content area and the background carries a 28pt strip of its own last colour below the drawn 520pt
# (scripts/render-mac-open-anyway.mjs). The window must stay inside 800 x 600 to open whole on a 1280 x 800 screen.
window_rect = ((200, 120), (720, 548))
default_view = "icon-view"
show_status_bar = False
show_tab_view = False
show_toolbar = False
show_pathbar = False
show_sidebar = False

icon_size = 128
text_size = 13
label_pos = "bottom"
arrange_by = None

icon_locations = {
    app_name: (190, 190),
    "Applications": (530, 190),
}
