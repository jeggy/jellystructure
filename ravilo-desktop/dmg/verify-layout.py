#!/usr/bin/env python3
"""Reads the Finder layout back out of a MOUNTED ravilo .dmg and fails if it is not the drawn window.

Usage: verify-layout.py <mount point>      (needs `ds_store`, the library dmgbuild writes the layout with)

DEB-1469 FR-4 / DEB-2122: the icon centres are the spec's numbers, the window is the spec's size plus the
title bar, the background is the combined 1x + 2x TIFF, and Finder has been told to draw it.
"""
import os
import sys

from ds_store import DSStore

mount = sys.argv[1]
failures = []


def expect(what, got, want):
    if got != want:
        failures.append(f"{what}: got {got!r}, want {want!r}")


store = os.path.join(mount, ".DS_Store")
if not os.path.isfile(store):
    sys.exit(f"no .DS_Store in {mount}: the window layout was not written")
if not os.path.isfile(os.path.join(mount, ".background.tiff")):
    sys.exit(f"no .background.tiff in {mount}: the 1x and 2x backgrounds were not combined")

with DSStore.open(store, "r") as d:
    expect("Ravilo.app icon centre", tuple(d["Ravilo.app"]["Iloc"])[:2], (190, 190))
    expect("Applications icon centre", tuple(d["Applications"]["Iloc"])[:2], (530, 190))
    bwsp = d["."]["bwsp"]
    icvp = d["."]["icvp"]

expect("window bounds", bwsp["WindowBounds"], "{{200, 120}, {720, 548}}")
for key in ("ShowToolbar", "ShowStatusBar", "ShowSidebar", "ShowPathbar"):
    expect(key, bool(bwsp[key]), False)
expect("background type (2 = a picture)", icvp["backgroundType"], 2)
expect("icon size", float(icvp["iconSize"]), 128.0)
if "backgroundImageAlias" not in icvp:
    failures.append("icvp has no backgroundImageAlias: Finder was not told which picture to draw")

if failures:
    print("\n".join("FAIL " + f for f in failures))
    sys.exit(1)
print("ok: window 720 x 548 incl. title bar, Ravilo.app at (190, 190), Applications at (530, 190), 1x+2x background")
