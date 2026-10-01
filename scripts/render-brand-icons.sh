#!/usr/bin/env bash
# R341 (dev review 12) — renders every committed brand raster from one placement table, so the Mac, iPhone, Linux and
# tab icons have one source. Phase 291's two admin rasters and R342's music Dock pictures come from here too.
#
#   scripts/render-brand-icons.sh [admin] [web] [mac] [dock]     (no argument = all four)
#
#   admin  src/wasmJsMain/resources/favicon.ico (16/32/48) + apple-touch-icon.png (180)      — from favicon.svg (291)
#   web    ravilo-web/…/favicon.ico (16/32) from favicon.svg (the bare jellyfish) + apple-touch-icon-180.png (R341)
#   mac    ravilo-desktop/icons/ravilo.icns + ravilo.png (512) + the byte-identical window icon ravilo-icon.png (R341)
#   dock   ravilo-desktop/icons/dock/music-*.png — R342's running music icon (M7b), every style, 512 + 32 px
#
# Each SVG is rasterised by headless Chromium (software only: --headless --disable-gpu; never a window, never Xvfb),
# then Pillow packs the .ico and this script packs the .icns. ImageMagick is not used: it renders the gradient flat.
# The outputs are committed and no build rasterises anything (264/R313's rule). Re-running is safe: the same inputs
# give the same files.
#
# Chromium: $CHROME, else the newest chrome-headless-shell in Playwright's cache (~/.cache/ms-playwright), else
# chromium / google-chrome on PATH. Pillow: python3 -c 'import PIL'.
set -euo pipefail
cd "$(dirname "$0")/.."

chrome="${CHROME:-}"
if [[ -z "$chrome" ]]; then
  for c in $(ls -d "$HOME"/.cache/ms-playwright/chromium_headless_shell-*/chrome-headless-shell-*/chrome-headless-shell 2>/dev/null | sort -V -r); do
    chrome="$c"; break
  done
fi
if [[ -z "$chrome" ]]; then
  chrome="$(command -v chromium || command -v chromium-browser || command -v google-chrome || true)"
fi
[[ -n "$chrome" && -x "$chrome" ]] || { echo "render-brand-icons: no headless Chromium found (set CHROME=…)" >&2; exit 1; }
python3 -c 'import PIL' 2>/dev/null || { echo "render-brand-icons: needs Pillow (python3 -m pip install pillow)" >&2; exit 1; }

exec python3 - "$chrome" "$@" <<'PY'
import io, os, struct, subprocess, sys, tempfile
from PIL import Image

CHROME = sys.argv[1]
TARGETS = set(sys.argv[2:]) or {"admin", "web", "mac", "dock"}
unknown = TARGETS - {"admin", "web", "mac", "dock"}
if unknown:
    sys.exit(f"render-brand-icons: unknown target(s) {sorted(unknown)}")

TMP = tempfile.mkdtemp(prefix="brand-icons-")

def render(svg: str, px: int) -> Image.Image:
    """One SVG, drawn by headless Chromium onto a transparent px × px page."""
    html = os.path.join(TMP, "p.html")
    png = os.path.join(TMP, "p.png")
    with open(html, "w") as f:
        f.write('<!doctype html><html><head><style>html,body{margin:0;padding:0;background:transparent;overflow:hidden}'
                'svg{display:block}</style></head><body>' + svg + '</body></html>')
    if os.path.exists(png):
        os.remove(png)
    subprocess.run([CHROME, "--headless=new","--disable-gpu", "--no-sandbox", "--hide-scrollbars",
                    "--force-device-scale-factor=1", "--default-background-color=00000000",
                    f"--window-size={px},{px}", f"--screenshot={png}", "file://" + html],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    im = Image.open(png).convert("RGBA")
    if im.size != (px, px):
        sys.exit(f"render-brand-icons: Chromium drew {im.size}, wanted {px}×{px}")
    return im

def svg_doc(px, viewbox, defs, body):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{px}" height="{px}" viewBox="{viewbox}">'
            f'<defs>{defs}</defs>{body}</svg>')

def save_png(im: Image.Image, path: str, opaque=False):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    (im.convert("RGB") if opaque else im).save(path, "PNG", optimize=True)
    print("  wrote", path, f"{im.size[0]}px")

# ─── The drawings ────────────────────────────────────────────────────────────────────────────────────────────────────
# Ravilo's master mark (design/ravilo/assets/brand/ravilo-mark.svg), in its raw path coordinates.
GRAD = ('<linearGradient id="rg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#AA5CC3"/>'
        '<stop offset="1" stop-color="#00A4DC"/></linearGradient>')
BELL = "M22 52 C22 24 78 24 78 52 C66 45 59 45 50 49 C41 45 34 45 22 52 Z"
TENT = ["M33 51 q-5 12 1 20 q5 8 0 14", "M44 52 q-4 13 1 21 q4 9 0 13",
        "M56 52 q4 13 -1 21 q-4 9 0 13", "M67 51 q5 12 -1 20 q-5 8 0 14"]
OP = [".9", ".72", ".72", ".9"]

def jelly(fill):
    t = "".join(f'<path d="{d}" opacity="{o}"/>' for d, o in zip(TENT, OP))
    return (f'<path d="{BELL}" fill="{fill}"/>'
            f'<g stroke="{fill}" stroke-width="4.5" stroke-linecap="round" fill="none">{t}</g>')

# R0's glow (dev review 8): stdDeviation 2.4 master units, scaled with the mark because the filter sits on its group.
GLOW = ('<filter id="glow" x="-40%" y="-40%" width="180%" height="180%">'
        '<feDropShadow dx="0" dy="0" stdDeviation="2.4" flood-color="#8a6ff0" flood-opacity=".6"/></filter>')
NAVY = "#000B25"

# ─── The placement table ─────────────────────────────────────────────────────────────────────────────────────────────
# FR-R341-1: on a 100-unit visible tile, the Mac/iPhone/Linux jellyfish is the master at scale 1.18, centred
# (bell x 22–78 → 16.96–83.04, about 66 %). Android and the other web icons keep today's size and are not rendered here.
MAC_MARK = "translate(-9 -20.21) scale(1.18)"

# Apple's macOS icon template on a 1024 canvas: the visible tile is the 824 px rounded square at (100, 100), radius
# 22.5 % (185 px), with a transparent margin and a baked shadow under it (R341 dev review 2–3). The shadow is drawn
# outside the tile only, so a translucent (Clear) tile is not darkened by it.
TEMPLATE = dict(canvas=1024, x=100, y=100, side=824)
SHADOW = ('<filter id="shadow" x="-10%" y="-10%" width="120%" height="125%">'
          '<feGaussianBlur in="SourceAlpha" stdDeviation="12"/><feOffset dy="10" result="b"/>'
          '<feFlood flood-color="#000" flood-opacity=".36"/><feComposite in2="b" operator="in"/>'
          '<feComposite in2="SourceAlpha" operator="out"/></filter>')

def on_template(px, defs, tile_body, shadow=True):
    """A 100-unit tile drawing placed in Apple's template at px × px."""
    t = TEMPLATE
    k = t["side"] / 100
    sh = (f'<rect x="{t["x"]}" y="{t["y"]}" width="{t["side"]}" height="{t["side"]}" rx="{22.5 * k}" '
          f'fill="#000" filter="url(#shadow)"/>') if shadow else ""
    body = sh + f'<g transform="translate({t["x"]} {t["y"]}) scale({k})">{tile_body}</g>'
    return svg_doc(px, f'0 0 {t["canvas"]} {t["canvas"]}', defs + SHADOW, body)

FILMS_TILE = (f'<rect width="100" height="100" rx="22.5" fill="{NAVY}"/>'
              f'<g filter="url(#glow)" transform="{MAC_MARK}">{jelly("url(#rg)")}</g>')

# ─── Phase 291 — the admin's tile ────────────────────────────────────────────────────────────────────────────────────
def admin():
    print("admin (291)")
    src = "src/wasmJsMain/resources/favicon.svg"
    svg = open(src).read().strip()
    def at(px, full_bleed=False):
        s = svg.replace('viewBox="0 0 100 100"', f'width="{px}" height="{px}" viewBox="0 0 100 100"', 1)
        if px == 16:     # dev review 5: centred, the gaps between the squares straddle a pixel edge and smear into two
            s = s.replace("translate(13 13)", "translate(9.875 9.875)", 1)   # half-tone columns; half a pixel up-left
                                                                              # puts each gap on one whole pixel column
        if full_bleed:   # dev review 4: iOS cuts its own corners, so the 180 px file is an opaque, unrounded square
            s = s.replace('rx="22"', 'rx="0"', 1)
        return s
    frames = [render(at(n), n) for n in (16, 32, 48)]
    frames[2].save("src/wasmJsMain/resources/favicon.ico", sizes=[(16, 16), (32, 32), (48, 48)],
                   append_images=frames[:2])
    print("  wrote src/wasmJsMain/resources/favicon.ico 16/32/48")
    save_png(render(at(180, True), 180), "src/wasmJsMain/resources/apple-touch-icon.png", opaque=True)

# ─── R341 — the web app's tab icon and iPhone icon ───────────────────────────────────────────────────────────────────
def web():
    print("web (R341)")
    base = "ravilo-web/src/wasmJsMain/resources"
    svg = open(f"{base}/favicon.svg").read().strip()
    def at(px):
        return svg.replace("<svg ", f'<svg width="{px}" height="{px}" ', 1)
    frames = [render(at(n), n) for n in (16, 32)]
    frames[1].save(f"{base}/favicon.ico", sizes=[(16, 16), (32, 32)], append_images=frames[:1])
    print(f"  wrote {base}/favicon.ico 16/32")
    # FR-R341-1/2, dev review 7: full-bleed opaque navy (iOS rounds it), the jellyfish at the Mac size, with the glow.
    touch = svg_doc(180, "0 0 100 100", GRAD + GLOW,
                    f'<rect width="100" height="100" fill="{NAVY}"/>'
                    f'<g filter="url(#glow)" transform="{MAC_MARK}">{jelly("url(#rg)")}</g>')
    save_png(render(touch, 180), f"{base}/apple-touch-icon-180.png", opaque=True)

# ─── R341 — the Mac and Linux icon ───────────────────────────────────────────────────────────────────────────────────
def write_icns(path, by_size):
    """The same chunk set the shipped file had (dev review 1): ic07–ic14 plus icp4/icp5, PNG payloads, no TOC."""
    order = [(b"ic07", 128), (b"ic08", 256), (b"ic09", 512), (b"ic10", 1024), (b"ic11", 32), (b"ic12", 64),
             (b"ic13", 256), (b"ic14", 512), (b"icp4", 16), (b"icp5", 32)]
    blobs = {}
    for _, n in order:
        if n not in blobs:
            b = io.BytesIO()
            by_size[n].save(b, "PNG", optimize=True)
            blobs[n] = b.getvalue()
    body = b"".join(t + struct.pack(">I", 8 + len(blobs[n])) + blobs[n] for t, n in order)
    with open(path, "wb") as f:
        f.write(b"icns" + struct.pack(">I", 8 + len(body)) + body)
    print("  wrote", path, "16…1024")

def mac():
    print("mac (R341)")
    defs = GRAD + GLOW
    by_size = {n: render(on_template(n, defs, FILMS_TILE), n) for n in (16, 32, 64, 128, 256, 512, 1024)}
    write_icns("ravilo-desktop/icons/ravilo.icns", by_size)
    save_png(by_size[512], "ravilo-desktop/icons/ravilo.png")
    # Dev review 4: the window and About icon is the same file, byte for byte.
    with open("ravilo-desktop/icons/ravilo.png", "rb") as a, \
         open("ravilo-desktop/src/desktopMain/resources/ravilo-icon.png", "wb") as b:
        b.write(a.read())
    print("  wrote ravilo-desktop/src/desktopMain/resources/ravilo-icon.png (= icons/ravilo.png)")

# ─── R342 — the running music icon (M7b), per macOS icon style ───────────────────────────────────────────────────────
# design/ravilo/Desktop - Music App Icon.html, app(): the films icon with a circle cut out of the jellyfish at (78, 78),
# a gradient ring and a white ♪. At 32 px and below the bubble is drawn bigger (FR-R342-1).
NOTE = ('<ellipse cx="0" cy="0" rx="9" ry="6.8" transform="rotate(-22)"/>'
        '<rect x="5.2" y="-38" width="4.6" height="38" rx="1.6"/>'
        '<path d="M5.2 -38 L9.8 -38 C11 -30 23 -28 21.5 -13 C21 -10 19.5 -7.5 18 -6 C19 -12 17.5 -20 9.8 -24.5 Z"/>')
CLEAR_SHADOW = ('<filter id="csh" x="-20%" y="-20%" width="140%" height="140%">'
                '<feDropShadow dx="0" dy="1" stdDeviation="1.2" flood-color="#1b2234" flood-opacity=".35"/></filter>')

def music_tile(small, style):
    """The 100-unit music tile. style: default | dark | clear-light | clear-dark | marks (one white mask) | tile."""
    R, cut, sw, note_t = (19, 21.5, 4.2, "translate(75 86.25) scale(.5)") if small else \
                         (15, 17, 2.6, "translate(75.48 85.01) scale(.42)")
    mono = {"clear-light": "#ffffff", "clear-dark": "rgba(255,255,255,.92)", "marks": "#ffffff"}.get(style)
    tile = {"default": NAVY, "dark": "#05070E", "clear-light": "rgba(255,255,255,.38)",
            "clear-dark": "rgba(18,22,32,.55)", "tile": "#ffffff"}.get(style)
    stroke = {"clear-light": ' stroke="rgba(255,255,255,.75)" stroke-width="1.2"',
              "clear-dark": ' stroke="rgba(255,255,255,.28)" stroke-width="1.2"'}.get(style, "")
    out = f'<rect x=".6" y=".6" width="98.8" height="98.8" rx="22.5" fill="{tile}"{stroke}/>' if tile else ""
    if style == "tile":
        return out
    jf = mono or "url(#rg)"
    filt = ' filter="url(#csh)"' if style == "clear-light" else ("" if mono else ' filter="url(#glow)"')
    bub_filt = ' filter="url(#csh)"' if style == "clear-light" else ""
    out += (f'<g mask="url(#cut)"><g{filt} transform="{MAC_MARK}">{jelly(jf)}</g></g>'
            f'<g{bub_filt}>'
            f'<circle cx="78" cy="78" r="{R}" fill="{"none" if mono else "rgba(255,255,255,.08)"}" '
            f'stroke="{mono or "url(#rg)"}" stroke-width="{sw}"/>'
            f'<g transform="{note_t}" fill="{mono or "#fff"}">{NOTE}</g></g>')
    return out

def music_defs(small):
    cut = 21.5 if small else 17
    return (GRAD + GLOW + CLEAR_SHADOW +
            f'<mask id="cut"><rect width="100" height="100" fill="#fff"/><circle cx="78" cy="78" r="{cut}" fill="#000"/></mask>')

def dock():
    print("dock (R342)")
    out = "ravilo-desktop/icons/dock"
    for px, suffix in ((512, ""), (32, "-32")):
        small = px <= 32
        defs = music_defs(small)
        # Whole pictures: Default, Dark, Clear light, Clear dark — each with the template's margin and shadow.
        for style in ("default", "dark", "clear-light", "clear-dark"):
            save_png(render(on_template(px, defs, music_tile(small, style)), px), f"{out}/music-{style}{suffix}.png")
        # Tinted: three one-colour layers the Swift library paints at run time (the tint is the viewer's own colour).
        save_png(render(on_template(px, defs, "", shadow=True), px), f"{out}/music-tinted-shadow{suffix}.png")
        save_png(render(on_template(px, defs, music_tile(small, "tile"), shadow=False), px),
                 f"{out}/music-tinted-tile{suffix}.png")
        save_png(render(on_template(px, defs, music_tile(small, "marks"), shadow=False), px),
                 f"{out}/music-tinted-marks{suffix}.png")

for name, fn in (("admin", admin), ("web", web), ("mac", mac), ("dock", dock)):
    if name in TARGETS:
        fn()
PY
