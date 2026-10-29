#!/usr/bin/env python3
"""Render this round's SVGs through Skia's own SVGDOM, which is what Ravilo Web,
macOS and Linux use — so a file that Chromium draws and Skia does not is caught here
rather than on a viewer's screen. Android goes through AndroidSVG, which is a third
renderer and is not covered; the report says so.

    <venv>/bin/python -I design/incoming/2026-10-29-collection-artwork/tools/skia-render.py

Writes png/collection-artwork-skia-<name>.png, one per SVG, at 3x on a mid-gradient
ground so white-on-light is visible, plus a transparent-ground copy at 1x.
"""

import os
import sys

import skia

ROUND = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROUND, "png")
SCALE = 3
W, H = 320, 100

# the mid-tone each file is judged against: its own collection's first stop
GROUND = {
    "sagaer": 0xFFA33B74,
    "tidsmaskinen": 0xFF9A7A2A,
    "serie-hub": 0xFF2A9168,
}


def ground_for(rel):
    for key, colour in GROUND.items():
        if key in rel:
            return colour
    return 0xFF333333


def render(rel):
    path = os.path.join(ROUND, rel)
    with open(path, "rb") as fh:
        data = skia.Data.MakeWithCopy(fh.read())
    stream = skia.MemoryStream(data)
    dom = skia.SVGDOM.MakeFromStream(stream)
    if dom is None:
        sys.exit(f"{rel}: Skia could not parse this SVG at all")
    surface = skia.Surface(W * SCALE, H * SCALE)
    with surface as canvas:
        canvas.clear(ground_for(rel))
        canvas.scale(SCALE, SCALE)
        dom.setContainerSize(skia.Size(W, H))
        dom.render(canvas)
    img = surface.makeImageSnapshot()
    name = rel.replace("/", "-").replace(".svg", "")
    out = os.path.join(OUT, f"collection-artwork-skia-{name}.png")
    img.save(out, skia.kPNG)
    return out


FILES = [
    "upload/sagaer.svg",
    "upload/tidsmaskinen.svg",
    "upload/b/sagaer.svg",
    "upload/b/tidsmaskinen.svg",
    "variants/tidsmaskinen-a-oneline.svg",
    "variants/tidsmaskinen-a-twoline.svg",
    "variants/tidsmaskinen-b-oneline.svg",
    "variants/tidsmaskinen-b-twoline.svg",
    "variants/tidsmaskinen-b-tittleclock.svg",
    "marks/serie-hub-a.svg",
    "marks/serie-hub-b.svg",
]

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for rel in FILES:
        print("skia:", os.path.basename(render(rel)))
