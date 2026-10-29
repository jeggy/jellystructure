#!/usr/bin/env python3
"""Build this round's logo SVGs, the frames' data file and the measured numbers.

Every letter in every output is an outlined <path>, taken from the repository's own
`ravilo-ui/src/commonMain/composeResources/font/space_grotesk.ttf` instanced to wght=700
(Space Grotesk Bold) and shaped with HarfBuzz, so the kerning is the font's own.

Run from the repository root with a venv holding fonttools, uharfbuzz:

    <venv>/bin/python -I design/incoming/2026-10-29-collection-artwork/tools/build-artwork.py

Nothing here is an instruction to any reader: it is the round's own prototype, kept beside
the round it drew, like a render.mjs. It writes only inside its own round directory.
"""

import json
import math
import os
import re
import sys
from io import BytesIO

from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
import uharfbuzz as hb

ROUND = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = os.path.abspath(os.path.join(ROUND, "..", "..", ".."))
FONT_SRC = os.path.join(
    REPO, "ravilo-ui/src/commonMain/composeResources/font/space_grotesk.ttf"
)

BOX_W, BOX_H = 320.0, 100.0

# ---------------------------------------------------------------- the type


class Typesetter:
    """Space Grotesk at one weight, shaped by HarfBuzz, emitted as outlined paths."""

    def __init__(self, path, weight=700):
        base = TTFont(path)
        self.upm = base["head"].unitsPerEm
        self.cap = base["OS/2"].sCapHeight
        inst = instantiateVariableFont(base, {"wght": weight}, inplace=False)
        buf = BytesIO()
        inst.save(buf)
        self.data = buf.getvalue()
        self.tt = TTFont(BytesIO(self.data))
        self.glyphset = self.tt.getGlyphSet()
        self.order = self.tt.getGlyphOrder()
        face = hb.Face(hb.Blob(self.data))
        self.hbfont = hb.Font(face)
        self.hbfont.scale = (self.upm, self.upm)
        self.weight = weight

    def shape(self, text):
        buf = hb.Buffer()
        buf.add_str(text)
        buf.guess_segment_properties()
        hb.shape(self.hbfont, buf)
        out, x = [], 0
        for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
            out.append((self.order[info.codepoint], x + pos.x_offset, pos.y_offset))
            x += pos.x_advance
        return out, x

    def advance(self, text, cap_height):
        _, adv = self.shape(text)
        return adv * cap_height / self.cap

    def cap_for_width(self, text, width):
        _, adv = self.shape(text)
        return width * self.cap / adv

    def ink_top(self, text):
        """How far the ink reaches above the baseline, in cap-height multiples.

        An accented capital is the reason this exists: É reaches 888 units against a
        700 cap, so laying a line out on its cap height alone clips the accent off the
        top of the box.
        """
        glyphs, _ = self.shape(text)
        top = 0
        for name, _gx, _gy in glyphs:
            pen = BoundsPen(self.glyphset)
            self.glyphset[name].draw(pen)
            if pen.bounds:
                top = max(top, pen.bounds[3])
        return top / self.cap

    def path(self, text, cap_height, x0, baseline):
        """One `d` string for `text`, cap height `cap_height`, left edge x0, baseline y."""
        s = cap_height / self.cap
        glyphs, _ = self.shape(text)
        svg = SVGPathPen(self.glyphset, ntos=lambda v: fmt(v))
        for name, gx, gy in glyphs:
            pen = TransformPen(svg, (s, 0, 0, -s, x0 + gx * s, baseline - gy * s))
            self.glyphset[name].draw(pen)
        return svg.getCommands()


def fmt(v):
    """Short, deterministic numbers: no 1e-17, no trailing zeros."""
    v = round(float(v) + 0.0, 2)
    if v == 0:
        v = 0.0
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


# ---------------------------------------------------------------- shape helpers


def rr(x, y, w, h, r, ccw=False):
    """A rounded-rectangle subpath. `ccw` reverses it so evenodd knocks it out."""
    r = min(r, w / 2, h / 2)
    f = fmt
    if not ccw:
        return (
            f"M{f(x + r)} {f(y)}H{f(x + w - r)}A{f(r)} {f(r)} 0 0 1 {f(x + w)} {f(y + r)}"
            f"V{f(y + h - r)}A{f(r)} {f(r)} 0 0 1 {f(x + w - r)} {f(y + h)}"
            f"H{f(x + r)}A{f(r)} {f(r)} 0 0 1 {f(x)} {f(y + h - r)}"
            f"V{f(y + r)}A{f(r)} {f(r)} 0 0 1 {f(x + r)} {f(y)}Z"
        )
    return (
        f"M{f(x + r)} {f(y)}A{f(r)} {f(r)} 0 0 0 {f(x)} {f(y + r)}"
        f"V{f(y + h - r)}A{f(r)} {f(r)} 0 0 0 {f(x + r)} {f(y + h)}"
        f"H{f(x + w - r)}A{f(r)} {f(r)} 0 0 0 {f(x + w)} {f(y + h - r)}"
        f"V{f(y + r)}A{f(r)} {f(r)} 0 0 0 {f(x + w - r)} {f(y)}Z"
    )


def tri(cx, cy, size, ccw=False):
    """A right-pointing play triangle with slightly softened corners, centred on cx,cy."""
    h = size
    w = size * 0.88
    pts = [(cx - w / 2, cy - h / 2), (cx + w / 2, cy), (cx - w / 2, cy + h / 2)]
    if ccw:
        pts = [pts[0], pts[2], pts[1]]
    f = fmt
    d = f"M{f(pts[0][0])} {f(pts[0][1])}"
    for px, py in pts[1:]:
        d += f"L{f(px)} {f(py)}"
    return d + "Z"


def tri_left(cx, cy, size, ccw=False):
    """A left-pointing triangle: the rewind counterpart of the play glyph."""
    h = size
    w = size * 0.88
    pts = [(cx + w / 2, cy - h / 2), (cx - w / 2, cy), (cx + w / 2, cy + h / 2)]
    if ccw:
        pts = [pts[0], pts[2], pts[1]]
    f = fmt
    d = f"M{f(pts[0][0])} {f(pts[0][1])}"
    for px, py in pts[1:]:
        d += f"L{f(px)} {f(py)}"
    return d + "Z"


def mark_rewind_badge(x, y, size):
    """A solid badge with a double rewind arrow knocked out of it.

    Direction B's small glyph for Tidsmaskinen. A thin open ring does not survive the
    13 px it gets on the tile — this is the same solid silhouette as Sagaer's link, which
    does, and it keeps the rewind reading at every size.
    """
    d = rr(x, y, size, size, size * 0.25)
    d += tri_left(x + size * 0.38, y + size / 2, size * 0.46, ccw=True)
    d += tri_left(x + size * 0.64, y + size / 2, size * 0.46, ccw=True)
    return [(d, {"fill": "#ffffff", "fill-rule": "evenodd"})]


def ring(cx, cy, r_out, r_in, a0, a1):
    """An open ring from angle a0 to a1 (degrees, 0 = up, clockwise), as one subpath."""
    f = fmt

    def pt(r, a):
        t = math.radians(a - 90)
        return cx + r * math.cos(t), cy + r * math.sin(t)

    large = 1 if abs(a1 - a0) > 180 else 0
    ox0, oy0 = pt(r_out, a0)
    ox1, oy1 = pt(r_out, a1)
    ix1, iy1 = pt(r_in, a1)
    ix0, iy0 = pt(r_in, a0)
    return (
        f"M{f(ox0)} {f(oy0)}A{f(r_out)} {f(r_out)} 0 {large} 1 {f(ox1)} {f(oy1)}"
        f"L{f(ix1)} {f(iy1)}A{f(r_in)} {f(r_in)} 0 {large} 0 {f(ix0)} {f(iy0)}Z"
    )


def arrowhead(cx, cy, size, rot):
    """A triangular arrowhead centred on cx,cy, rotated `rot` degrees from pointing right."""
    f = fmt
    pts = [(-size * 0.45, -size * 0.55), (size * 0.55, 0.0), (-size * 0.45, size * 0.55)]
    t = math.radians(rot)
    co, si = math.cos(t), math.sin(t)
    out = [(cx + px * co - py * si, cy + px * si + py * co) for px, py in pts]
    d = f"M{f(out[0][0])} {f(out[0][1])}"
    for px, py in out[1:]:
        d += f"L{f(px)} {f(py)}"
    return d + "Z"


# ---------------------------------------------------------------- the marks
#
# Each mark is drawn in its own box and says what the collection is by varying the
# play glyph, which is what every logo already on the rail does.


def mark_serie_hub(x, y, w, h):
    """An offset stack of episode frames: many of the same thing, one behind the next."""
    # Drawn in a 68x58 local frame, then placed.
    sx, sy = x, y
    k = min(w / 68.0, h / 58.0)

    def X(v):
        return sx + v * k

    def Y(v):
        return sy + v * k

    def S(v):
        return v * k

    parts = []
    # the two frames behind are centred on the front one: a stack, not a fan
    parts.append(
        (
            rr(X(10), Y(0), S(42), S(7), S(3.5)),
            {"fill": "#ffffff", "fill-opacity": "0.45"},
        )
    )
    parts.append(
        (
            rr(X(5), Y(10), S(52), S(7), S(3.5)),
            {"fill": "#ffffff", "fill-opacity": "0.7"},
        )
    )
    front = rr(X(0), Y(20), S(62), S(38), S(8)) + tri(
        X(31), Y(39), S(19), ccw=True
    )
    parts.append((front, {"fill": "#ffffff", "fill-rule": "evenodd"}))
    return parts


def mark_sagaer(x, y, w, h):
    """Three frames linked in a row: one film after another, in release order."""
    # 96x28 local frame: square 28, link 6.
    k = min(w / 96.0, h / 28.0)

    def X(v):
        return x + v * k

    def Y(v):
        return y + v * k

    def S(v):
        return v * k

    parts = []
    # two open frames, then a solid one with the play cut out
    for i in (0, 1):
        ox = i * 34.0
        d = rr(X(ox), Y(0), S(28), S(28), S(7)) + rr(
            X(ox + 5.5), Y(5.5), S(17), S(17), S(3), ccw=True
        )
        parts.append((d, {"fill": "#ffffff", "fill-rule": "evenodd"}))
    for i in (0, 1):
        ox = 28.0 + i * 34.0
        parts.append((rr(X(ox), Y(11.5), S(6), S(5), S(2.5)), {"fill": "#ffffff"}))
    d = rr(X(68), Y(0), S(28), S(28), S(7)) + tri(X(82.6), Y(14), S(14), ccw=True)
    parts.append((d, {"fill": "#ffffff", "fill-rule": "evenodd"}))
    return parts


def mark_one_link(x, y, size):
    """One link of the chain on its own: direction B's small glyph for Sagaer."""
    d = rr(x, y, size, size, size * 0.25) + tri(
        x + size * 0.52, y + size / 2, size * 0.5, ccw=True
    )
    return [(d, {"fill": "#ffffff", "fill-rule": "evenodd"})]


def mark_episode_pip(x, y, w, h):
    """The episode stack at glyph scale: direction B's small glyph for the series hub."""
    k = min(w / 44.0, h / 40.0)

    def X(v):
        return x + v * k

    def Y(v):
        return y + v * k

    def S(v):
        return v * k

    parts = [
        (rr(X(7), Y(0), S(30), S(5), S(2.5)), {"fill": "#ffffff", "fill-opacity": "0.5"}),
        (rr(X(3), Y(7.5), S(38), S(5), S(2.5)), {"fill": "#ffffff", "fill-opacity": "0.75"}),
        (
            rr(X(0), Y(15), S(44), S(25), S(6)) + tri(X(22), Y(27.5), S(13), ccw=True),
            {"fill": "#ffffff", "fill-rule": "evenodd"},
        ),
    ]
    return parts


def mark_tidsmaskinen(x, y, w, h, gap_deg=66.0):
    """A rewind ring around the play glyph: the same catalogue, wound backwards."""
    # 58x58 local frame, centred ring.
    k = min(w / 58.0, h / 58.0)
    cx, cy = x + 29 * k, y + 29 * k
    r_out, r_in = 28 * k, 20 * k
    parts = []
    # the ring runs clockwise from just past the top, leaving a gap at the top-left
    parts.append((ring(cx, cy, r_out, r_in, gap_deg / 2, 360 - gap_deg / 2), {"fill": "#ffffff"}))
    # the arrowhead sits at the counter-clockwise end of the ring, pointing back
    a = math.radians(-gap_deg / 2 - 90)
    hx, hy = cx + (r_out + r_in) / 2 * math.cos(a), cy + (r_out + r_in) / 2 * math.sin(a)
    parts.append((arrowhead(hx, hy, 21 * k, -gap_deg / 2 + 180 + 14), {"fill": "#ffffff"}))
    parts.append((tri(cx + 1.5 * k, cy, 20 * k), {"fill": "#ffffff"}))
    return parts


def mark_clock_tittle(cx, cy, r):
    """The tiny clock that stands in for an i's tittle in direction B."""
    parts = [(ring(cx, cy, r, r * 0.56, 0, 359.9), {"fill": "#ffffff"})]
    # two hands, pointing to ten past ten, drawn as capsules
    for ang, ln in ((-60.0, 0.74), (35.0, 0.52)):
        t = math.radians(ang - 90)
        ex, ey = cx + r * ln * math.cos(t), cy + r * ln * math.sin(t)
        w = r * 0.19
        nx, ny = -math.sin(t) * w, math.cos(t) * w
        f = fmt
        parts.append(
            (
                f"M{f(cx + nx)} {f(cy + ny)}L{f(ex + nx)} {f(ey + ny)}"
                f"L{f(ex - nx)} {f(ey - ny)}L{f(cx - nx)} {f(cy - ny)}Z",
                {"fill": "#ffffff"},
            )
        )
    return parts


def mark_sequence_pip(x, y, size):
    """Direction B's small glyph for Sagaer: two dots and a play, the sequence in miniature."""
    parts = []
    r = size * 0.17
    for i in range(2):
        cx = x + r + i * (size * 0.36)
        parts.append(
            (
                f"M{fmt(cx - r)} {fmt(y + size / 2)}"
                f"a{fmt(r)} {fmt(r)} 0 1 0 {fmt(2 * r)} 0"
                f"a{fmt(r)} {fmt(r)} 0 1 0 {fmt(-2 * r)} 0Z",
                {"fill": "#ffffff", "fill-opacity": "0.6"},
            )
        )
    parts.append((tri(x + size * 0.84, y + size / 2, size * 0.78), {"fill": "#ffffff"}))
    return parts


# ---------------------------------------------------------------- SVG assembly


def svg(parts, label, box=(BOX_W, BOX_H)):
    w, h = box
    body = "\n".join(
        "  <path "
        + " ".join(f'{k}="{v}"' for k, v in attrs.items())
        + f' d="{d}"/>'
        for d, attrs in parts
    )
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {fmt(w)} {fmt(h)}" '
        f'width="{fmt(w)}" height="{fmt(h)}" role="img" aria-label="{label}">\n'
        f"{body}\n</svg>\n"
    )


FORBIDDEN = re.compile(r"<text|<svg:text|<style|<filter|<image|<foreignObject|<script|class=|href", re.I)


def check(svg_text, name):
    hit = FORBIDDEN.search(svg_text)
    if hit:
        sys.exit(f"{name}: forbidden construct {hit.group(0)!r}")
    return svg_text


def write(rel, text):
    path = os.path.join(ROUND, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(check(text, rel))
    return rel


# ---------------------------------------------------------------- colour


def lum(h):
    h = h.lstrip("#")
    ch = [int(h[i : i + 2], 16) / 255 for i in (0, 2, 4)]
    f = lambda c: c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = [f(c) for c in ch]
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def white_contrast(h):
    return round(1.05 / (lum(h) + 0.05), 2)


def stops_of(brand):
    return re.findall(r"#[0-9a-fA-F]{6}", brand)


# ---------------------------------------------------------------- the round

ts = Typesetter(FONT_SRC, 700)
metrics = {
    "font": {
        "file": "ravilo-ui/src/commonMain/composeResources/font/space_grotesk.ttf",
        "family": "Space Grotesk",
        "instance": "wght=700 (Bold), instanced from the variable font",
        "unitsPerEm": ts.upm,
        "capHeight": ts.cap,
    },
    "logos": {},
}

BRAND = {
    "serie-hub": "linear-gradient(135deg, #2a9168 0%, #0d3b2a 100%)",
    "sagaer": "linear-gradient(135deg, #a33b74 0%, #3a0f33 100%)",
    "tidsmaskinen": "linear-gradient(135deg, #9a7a2a 0%, #3a2a0c 100%)",
}

# ---- direction A: house badge (mark left, wordmark right) -------------------

A_BASELINE = 68.0
A_CAP = 40.0


def build_a_sagaer():
    parts = mark_sagaer(10, 36, 96, 28)
    x0 = 118.0
    cap = A_CAP
    adv = ts.advance("Sagaer", cap)
    avail = BOX_W - x0 - 14
    if adv > avail:
        cap = ts.cap_for_width("Sagaer", avail)
        adv = avail
    parts.append((ts.path("Sagaer", cap, x0, A_BASELINE), {"fill": "#ffffff"}))
    return parts, {"capHeight": round(cap, 2), "wordWidth": round(adv, 1), "lines": 1}


def build_a_tidsmaskinen(two_line=False, gap=66.0):
    parts = mark_tidsmaskinen(10, 21, 58, 58)
    x0 = 80.0
    avail = BOX_W - x0 - 14
    if not two_line:
        cap = min(A_CAP, ts.cap_for_width("Tidsmaskinen", avail))
        parts.append((ts.path("Tidsmaskinen", cap, x0, A_BASELINE), {"fill": "#ffffff"}))
        adv = ts.advance("Tidsmaskinen", cap)
        info = {"capHeight": round(cap, 2), "wordWidth": round(adv, 1), "lines": 1}
    else:
        widest = max(["Tids", "maskinen"], key=lambda w: ts.advance(w, 10))
        cap = min(32.0, ts.cap_for_width(widest, avail))
        lead = cap * 1.26
        top = 50.0 - lead / 2 + cap / 2
        for i, word in enumerate(("Tids", "maskinen")):
            parts.append(
                (ts.path(word, cap, x0, top + i * lead), {"fill": "#ffffff"})
            )
        info = {
            "capHeight": round(cap, 2),
            "wordWidth": round(ts.advance(widest, cap), 1),
            "lines": 2,
            "leading": round(lead, 2),
        }
    return parts, info


def build_a_serie_hub():
    """Mark only — kept as the record of the hold, before the name was picked."""
    return mark_serie_hub(10, 21, 68, 58), {"capHeight": None, "lines": 0}


# The series hub's name, decided by the owner on 2026-10-29. Two lines, split where the
# phrase breaks: on one line the lettering falls to 72% of Sagaer's cap height, on two it
# holds 94%. "Ét / afsnit til" measures a hair wider still (94.5%) and is not used — it
# orphans the article and reads as a different phrase.
HUB_LINES = ("Ét afsnit", "til")


def build_a_et_afsnit_til():
    parts = mark_serie_hub(10, 21, 68, 58)
    x0 = 90.0
    avail = BOX_W - x0 - 14
    widest = max(HUB_LINES, key=lambda w: ts.advance(w, 10))

    # Both vertical numbers come from the lines' own ink rather than from a ratio, because
    # this wordmark has an accent on top and ascenders underneath it. The É reaches 27%
    # above the cap line, so the top clearance is measured from the accent; and `til`
    # leads with ascenders, so the leading is that line's own ascent plus a gap, or the
    # two lines crowd no matter what the cap height is.
    accent = ts.ink_top(HUB_LINES[0])
    ascent2 = ts.ink_top(HUB_LINES[1])
    gap = 0.18
    margin = 5.0
    cap = min(
        A_CAP,
        ts.cap_for_width(widest, avail),
        (BOX_H - 2 * margin) / (accent + ascent2 + gap),
    )
    lead = (ascent2 + gap) * cap
    block = accent * cap + lead
    baseline = (BOX_H - block) / 2 + accent * cap
    for i, line in enumerate(HUB_LINES):
        parts.append((ts.path(line, cap, x0, baseline + i * lead), {"fill": "#ffffff"}))
    return parts, {
        "capHeight": round(cap, 2),
        "wordWidth": round(ts.advance(widest, cap), 1),
        "lines": 2,
        "leading": round(lead, 2),
        "accentAboveCap": round(accent - 1, 4),
        "inkTop": round(baseline - accent * cap, 2),
        "inkBottom": round(baseline + lead, 2),
    }


def build_a_et_afsnit_til_oneline():
    """Drawn for the comparison only: one line costs a quarter of the cap height."""
    parts = mark_serie_hub(10, 21, 68, 58)
    x0 = 90.0
    word = " ".join(HUB_LINES)
    cap = min(A_CAP, ts.cap_for_width(word, BOX_W - x0 - 14))
    parts.append((ts.path(word, cap, x0, A_BASELINE), {"fill": "#ffffff"}))
    return parts, {"capHeight": round(cap, 2), "wordWidth": round(ts.advance(word, cap), 1), "lines": 1}


# ---- direction B: wordmark-led ---------------------------------------------


def build_b_sagaer():
    """One link of the chain, then the lettering at the size that carries the logo."""
    glyph = 34.0
    x0 = 12.0 + glyph + 14.0
    cap = min(62.0, ts.cap_for_width("Sagaer", BOX_W - x0 - 14))
    baseline = 50.0 + cap / 2
    parts = mark_one_link(12.0, 50.0 - glyph / 2, glyph)
    parts.append((ts.path("Sagaer", cap, x0, baseline), {"fill": "#ffffff"}))
    return parts, {"capHeight": round(cap, 2), "wordWidth": round(ts.advance("Sagaer", cap), 1),
                   "lines": 1, "glyph": "one link, 34"}


def build_b_tidsmaskinen(two_line=True):
    """The lettering carries it. On two lines the rewind ring fills the gap after Tids."""
    parts = []
    if not two_line:
        # One line leaves no room for a glyph at all: the lettering is the whole logo.
        cap = min(44.0, ts.cap_for_width("Tidsmaskinen", BOX_W - 28))
        baseline = 50.0 + cap / 2
        word = "Tidsmaskinen"
        x0 = (BOX_W - ts.advance(word, cap)) / 2
        parts.append((ts.path(word, cap, x0, baseline), {"fill": "#ffffff"}))
        info = {"capHeight": round(cap, 2), "wordWidth": round(ts.advance(word, cap), 1),
                "lines": 1, "glyph": None}
    else:
        cap = 36.0
        lead = cap * 1.22
        top = 50.0 - lead / 2 + cap / 2
        widest = ts.advance("maskinen", cap)
        x0 = (BOX_W - widest) / 2
        for i, word in enumerate(("Tids", "maskinen")):
            parts.append((ts.path(word, cap, x0, top + i * lead), {"fill": "#ffffff"}))
        # the badge sits in the gap the short first line leaves, at cap height
        badge = cap * 0.92
        rx = x0 + ts.advance("Tids", cap) + cap * 0.34
        parts += mark_rewind_badge(rx, top - cap - (badge - cap) / 2, badge)
        info = {"capHeight": round(cap, 2), "wordWidth": round(widest, 1), "lines": 2,
                "leading": round(lead, 2), "glyph": f"rewind badge, {round(badge, 1)}"}
    return parts, info


def build_b_tittle_clock():
    """Drawn and failed: the tittle of the first i as a clock. Kept so the try is visible."""
    cap = min(44.0, ts.cap_for_width("Tidsmaskinen", BOX_W - 28))
    baseline = 50.0 + cap / 2
    word = "Tidsmaskinen"
    x0 = (BOX_W - ts.advance(word, cap)) / 2
    parts = [(ts.path(word, cap, x0, baseline), {"fill": "#ffffff"})]
    pre = ts.advance("Tid", cap) - ts.advance("d", cap)
    cx = x0 + pre + ts.advance("i", cap) * 0.42
    r = cap * 0.175
    parts += mark_clock_tittle(cx, baseline - cap - r * 1.15, r)
    return parts, {"capHeight": round(cap, 2), "lines": 1, "glyph": f"tittle clock, r={round(r, 2)}",
                   "failed": "the ring is 7.7 px across on the tile and reads as a smudge"}


def build_b_serie_hub():
    """Direction B is the lettering, and the lettering is on hold. Glyph only.

    Drawn where B puts its glyph — left of the word, as Sagaer's link is — so the tile
    shows the glyph at the size and place the finished lockup will give it.
    """
    return mark_episode_pip(12, 50 - 19, 42, 38), {"capHeight": None, "lines": 0}


def build_stand_in():
    """The five tiles already on the rail, drawn as the house shape and nothing more.

    Their real logos are third-party trademarks and their names identify the library,
    so neither appears anywhere in this round.
    """
    size = 56.0
    parts = mark_one_link(14.0, 50 - size / 2, size)
    parts.append((rr(86, 38, 190, 24, 12), {"fill": "#ffffff", "fill-opacity": "0.92"}))
    return parts, {"capHeight": None, "lines": 0}


# ---- emit -------------------------------------------------------------------

written = []

builds = {
    "upload/sagaer.svg": ("Sagaer", build_a_sagaer()),
    "upload/tidsmaskinen.svg": ("Tidsmaskinen", build_a_tidsmaskinen(two_line=True)),
    "upload/b/sagaer.svg": ("Sagaer", build_b_sagaer()),
    "upload/b/tidsmaskinen.svg": ("Tidsmaskinen", build_b_tidsmaskinen(two_line=True)),
    "variants/tidsmaskinen-a-oneline.svg": ("Tidsmaskinen", build_a_tidsmaskinen(False)),
    "variants/tidsmaskinen-a-twoline.svg": ("Tidsmaskinen", build_a_tidsmaskinen(True)),
    "variants/tidsmaskinen-b-oneline.svg": ("Tidsmaskinen", build_b_tidsmaskinen(False)),
    "variants/tidsmaskinen-b-twoline.svg": ("Tidsmaskinen", build_b_tidsmaskinen(True)),
    "variants/tidsmaskinen-b-tittleclock.svg": ("Tidsmaskinen", build_b_tittle_clock()),
    # The series hub, named by the owner on 2026-10-29. Direction A only: B gets nothing more.
    "upload/et-afsnit-til.svg": ("Ét afsnit til", build_a_et_afsnit_til()),
    "variants/et-afsnit-til-a-oneline.svg": ("Ét afsnit til", build_a_et_afsnit_til_oneline()),
    # The series hub: mark and gradient only, no wordmark, and deliberately NOT under upload/.
    "marks/serie-hub-a.svg": ("Series hub mark, name pending", build_a_serie_hub()),
    "marks/serie-hub-b.svg": ("Series hub mark, name pending", build_b_serie_hub()),
    # The neutral stand-in for the five collections already on the rail: the house shape
    # (a mark plus a bar) and nothing that could identify what they are.
    "marks/stand-in.svg": ("Placeholder", build_stand_in()),
}

for rel, (label, (parts, info)) in builds.items():
    written.append(write(rel, svg(parts, label)))
    metrics["logos"][rel] = info

# contrast, per stop, per collection
metrics["brand"] = {}
for key, brand in BRAND.items():
    st = stops_of(brand)
    metrics["brand"][key] = {
        "brand_color": brand,
        "stops": [{"hex": h, "whiteContrast": white_contrast(h), "luminance": round(lum(h), 4)} for h in st],
    }

# the five existing tiles, as stand-ins, measured the same way
EXISTING = [
    "#e0763d",
    "linear-gradient(135deg,#e50914,#831010)",
    "linear-gradient(135deg, #7FD6F2 0%, #35B7E4 60%, #2298C4 100%)",
    "linear-gradient(135deg,#1455d8,#0a2766)",
    "linear-gradient(135deg,#0a93a6,#063d47)",
]
metrics["existing"] = [
    {"brand_color": b, "stops": [{"hex": h, "whiteContrast": white_contrast(h)} for h in stops_of(b)]}
    for b in EXISTING
]

PADDING = {
    "serie-hub": {"top": 14, "right": 20, "bottom": 14, "left": 20},
    "sagaer": {"top": 12, "right": 18, "bottom": 12, "left": 18},
    "tidsmaskinen": {"top": 12, "right": 18, "bottom": 12, "left": 18},
}
metrics["padding_logo"] = PADDING

with open(os.path.join(ROUND, "metrics.json"), "w", encoding="utf-8") as fh:
    json.dump(metrics, fh, indent=2, sort_keys=True)
    fh.write("\n")

# ---- the one array the frames derive every number from ----------------------

data = {
    "tile": {"w": 224, "h": 94, "corner": 13, "focusScale": 1.08, "ringWidth": 3,
             "glowElev": 22, "sheenAlpha": 0.08, "watermarkAlpha": 0.06,
             "watermarkSize": 29, "watermarkPadStart": 12, "watermarkPadBottom": 8,
             "watermarkTake": 8},
    "themes": {"aurora": {"page": "#0A0C13", "ring": "#8E82FF"},
               "daylight": {"page": "#FBFBFD", "ring": "#5B4EE0"}},
    "existing": [{"id": f"stand-in-{i + 1}", "brand": b} for i, b in enumerate(EXISTING)],
    "new": [
        # Direction A carries the finished logo and the real watermark; direction B keeps
        # the mark it was drawn with, and its pending slot, because B gets nothing more.
        {"id": "et-afsnit-til", "bId": "serie-hub", "watermark": "ÉT AFSNI",
         "brand": BRAND["serie-hub"], "padding": PADDING["serie-hub"],
         "a": "upload/et-afsnit-til.svg", "b": "marks/serie-hub-b.svg",
         "nameOnHold": False, "bNameOnHold": True},
        {"id": "sagaer", "watermark": "SAGAER", "brand": BRAND["sagaer"],
         "padding": PADDING["sagaer"], "a": "upload/sagaer.svg", "b": "upload/b/sagaer.svg",
         "nameOnHold": False},
        {"id": "tidsmaskinen", "watermark": "TIDSMASK", "brand": BRAND["tidsmaskinen"],
         "padding": PADDING["tidsmaskinen"], "a": "upload/tidsmaskinen.svg",
         "b": "upload/b/tidsmaskinen.svg", "nameOnHold": False},
    ],
    "variants": [
        {"id": "tidsmaskinen-a-oneline", "file": "variants/tidsmaskinen-a-oneline.svg",
         "brand": BRAND["tidsmaskinen"], "watermark": "TIDSMASK", "padding": PADDING["tidsmaskinen"]},
        {"id": "tidsmaskinen-a-twoline", "file": "variants/tidsmaskinen-a-twoline.svg",
         "brand": BRAND["tidsmaskinen"], "watermark": "TIDSMASK", "padding": PADDING["tidsmaskinen"]},
        {"id": "tidsmaskinen-b-oneline", "file": "variants/tidsmaskinen-b-oneline.svg",
         "brand": BRAND["tidsmaskinen"], "watermark": "TIDSMASK", "padding": PADDING["tidsmaskinen"]},
        {"id": "tidsmaskinen-b-twoline", "file": "variants/tidsmaskinen-b-twoline.svg",
         "brand": BRAND["tidsmaskinen"], "watermark": "TIDSMASK", "padding": PADDING["tidsmaskinen"]},
        {"id": "et-afsnit-til-a-oneline", "file": "variants/et-afsnit-til-a-oneline.svg",
         "brand": BRAND["serie-hub"], "watermark": "ÉT AFSNI", "padding": PADDING["serie-hub"]},
        {"id": "et-afsnit-til-a-twoline", "file": "upload/et-afsnit-til.svg",
         "brand": BRAND["serie-hub"], "watermark": "ÉT AFSNI", "padding": PADDING["serie-hub"]},
    ],
    "metrics": metrics,
}

with open(os.path.join(ROUND, "artwork-data.js"), "w", encoding="utf-8") as fh:
    fh.write("// Generated by tools/build-artwork.py. Every number in every frame comes from here.\n")
    fh.write("window.ARTWORK = ")
    json.dump(data, fh, indent=2, sort_keys=True)
    fh.write(";\n")

for rel in written:
    print("wrote", rel)
print("wrote metrics.json, artwork-data.js")
for key, m in metrics["brand"].items():
    worst = min(s["whiteContrast"] for s in m["stops"])
    print(f"  {key}: worst white contrast {worst}:1")
for rel, info in metrics["logos"].items():
    print(f"  {rel}: {info}")
