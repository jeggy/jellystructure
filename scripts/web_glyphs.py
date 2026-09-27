#!/usr/bin/env python3
"""R315 — every character Ravilo draws as text must be in a font the web app bundles.

Compose on the web has no system fonts (Compose Multiplatform 1.9.3; automatic fallback arrives in
1.12.0), so a character none of the bundled fonts contains is drawn as a replacement box. This scans
every Kotlin string literal in ravilo-ui/src/commonMain (LanguageIdentity.kt's native language names
included) and every value in i18n/*.json, reads the bundled fonts' `cmap` tables itself (no fontTools:
the build gains no dependency), and fails naming each character none of them has.

    python3 scripts/web_glyphs.py            # the check (exit 1 on a missing character)
    python3 scripts/web_glyphs.py --needed   # print the characters the fallback font must carry

`scripts/build-web-fallback-font.py` uses the same scan to decide what goes into the fallback font.
"""
import glob
import json
import os
import struct
import sys
import unicodedata

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT_DIR = os.path.join(ROOT, "ravilo-ui/src/commonMain/composeResources/font")
# The fallback is web-only (Android keeps its system fallback), so it lives in wasmJsMain's resources.
FALLBACK_DIR = os.path.join(ROOT, "ravilo-ui/src/wasmJsMain/composeResources/font")
BASE_FONTS = ["sora.ttf", "space_grotesk.ttf"]
FALLBACK_FONT = "web_fallback.ttf"


def cmap_codepoints(path):
    """The code points a TrueType/OpenType font maps, from its Unicode cmap subtables (formats 4, 12)."""
    data = open(path, "rb").read()
    num_tables = struct.unpack(">H", data[4:6])[0]
    cmap_off = None
    for i in range(num_tables):
        tag, _, off, _ = struct.unpack(">4sIII", data[12 + 16 * i: 28 + 16 * i])
        if tag == b"cmap":
            cmap_off = off
    if cmap_off is None:
        return set()
    n = struct.unpack(">H", data[cmap_off + 2: cmap_off + 4])[0]
    cps = set()
    for i in range(n):
        plat, enc, sub = struct.unpack(">HHI", data[cmap_off + 4 + 8 * i: cmap_off + 12 + 8 * i])
        if not (plat == 0 or (plat == 3 and enc in (1, 10))):
            continue
        o = cmap_off + sub
        fmt = struct.unpack(">H", data[o: o + 2])[0]
        if fmt == 4:
            segx2 = struct.unpack(">H", data[o + 6: o + 8])[0]
            seg = segx2 // 2
            ends = struct.unpack(">%dH" % seg, data[o + 14: o + 14 + segx2])
            starts_off = o + 16 + segx2
            starts = struct.unpack(">%dH" % seg, data[starts_off: starts_off + segx2])
            deltas = struct.unpack(">%dh" % seg, data[starts_off + segx2: starts_off + 2 * segx2])
            ro_off = starts_off + 2 * segx2
            ros = struct.unpack(">%dH" % seg, data[ro_off: ro_off + segx2])
            for s in range(seg):
                for c in range(starts[s], ends[s] + 1):
                    if c == 0xFFFF:
                        continue
                    if ros[s] == 0:
                        gid = (c + deltas[s]) & 0xFFFF
                    else:
                        p = ro_off + 2 * s + ros[s] + 2 * (c - starts[s])
                        gid = struct.unpack(">H", data[p: p + 2])[0]
                        if gid:
                            gid = (gid + deltas[s]) & 0xFFFF
                    if gid:
                        cps.add(c)
        elif fmt == 12:
            ngroups = struct.unpack(">I", data[o + 12: o + 16])[0]
            for g in range(ngroups):
                start, end, gid = struct.unpack(">III", data[o + 16 + 12 * g: o + 28 + 12 * g])
                for c in range(start, end + 1):
                    if gid + (c - start):
                        cps.add(c)
    return cps


def kotlin_literals(src):
    """(line, text) of every string and char literal in Kotlin source, comments skipped, \\uXXXX decoded.
    Template expressions (`${...}`) are skipped as code; string literals nested inside them are kept."""
    out = []
    i, n, line = 0, len(src), 1
    buf = []

    def flush(start_line):
        if buf:
            out.append((start_line, "".join(buf)))
            buf.clear()

    def scan_string(i, raw, start_line):
        # returns index after the closing quote
        nonlocal line
        while i < n:
            ch = src[i]
            if raw and src.startswith('"""', i):
                j = i + 3
                while j < n and src[j] == '"':
                    buf.append('"'); j += 1
                return j
            if not raw and ch == '"':
                return i + 1
            if ch == "\n":
                line += 1
                if not raw:
                    return i
            if not raw and ch == "\\" and i + 1 < n:
                nx = src[i + 1]
                if nx == "u" and i + 5 < n:
                    try:
                        buf.append(chr(int(src[i + 2: i + 6], 16))); i += 6; continue
                    except ValueError:
                        pass
                i += 2
                continue
            if ch == "$" and i + 1 < n and src[i + 1] == "{":
                # template expression: skip code, but scan any string literal inside it
                flush(start_line)
                depth, i = 1, i + 2
                while i < n and depth:
                    c2 = src[i]
                    if c2 == "{":
                        depth += 1
                    elif c2 == "}":
                        depth -= 1
                    elif c2 == '"':
                        sl = line
                        if src.startswith('"""', i):
                            i = scan_string(i + 3, True, sl); flush(sl); continue
                        i = scan_string(i + 1, False, sl); flush(sl); continue
                    elif c2 == "\n":
                        line += 1
                    i += 1
                continue
            buf.append(ch)
            i += 1
        return i

    while i < n:
        ch = src[i]
        if ch == "\n":
            line += 1; i += 1; continue
        if src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
            continue
        if src.startswith("/*", i):
            depth, i = 1, i + 2
            while i < n and depth:
                if src.startswith("/*", i):
                    depth += 1; i += 2
                elif src.startswith("*/", i):
                    depth -= 1; i += 2
                else:
                    if src[i] == "\n":
                        line += 1
                    i += 1
            continue
        if src.startswith('"""', i):
            sl = line
            i = scan_string(i + 3, True, sl); flush(sl); continue
        if ch == '"':
            sl = line
            i = scan_string(i + 1, False, sl); flush(sl); continue
        if ch == "'":
            j = src.find("'", i + 1)
            if 0 < j - i <= 8:
                lit = src[i + 1: j]
                if lit.startswith("\\u"):
                    try:
                        lit = chr(int(lit[2:6], 16))
                    except ValueError:
                        pass
                out.append((line, lit))
                i = j + 1
                continue
        i += 1
    return out


def relevant(c):
    """A character a font must draw: not ASCII, not a control, format or variation mark."""
    if ord(c) < 0x80:
        return False
    cat = unicodedata.category(c)
    if cat in ("Cc", "Cf", "Co", "Cs"):
        return False
    if 0xFE00 <= ord(c) <= 0xFE0F:     # variation selectors
        return False
    return True


def used_characters():
    """{char: [where, ...]} over the app's Kotlin string literals and the i18n tables."""
    used = {}
    for path in sorted(glob.glob(os.path.join(ROOT, "ravilo-ui/src/commonMain/kotlin/**/*.kt"), recursive=True)):
        rel = os.path.relpath(path, ROOT)
        for line, text in kotlin_literals(open(path, encoding="utf-8").read()):
            for c in text:
                if relevant(c):
                    used.setdefault(c, []).append(f"{rel}:{line}")
    for path in sorted(glob.glob(os.path.join(ROOT, "i18n/*.json"))):
        rel = os.path.relpath(path, ROOT)
        for key, value in json.load(open(path, encoding="utf-8")).items():
            if isinstance(value, str):
                for c in value:
                    if relevant(c):
                        used.setdefault(c, []).append(f"{rel} {key}")
    return used


def font_coverage(name):
    path = os.path.join(FALLBACK_DIR if name == FALLBACK_FONT else FONT_DIR, name)
    return cmap_codepoints(path) if os.path.exists(path) else set()


def base_coverage():
    """What text in EITHER family draws without the fallback: Compose falls back only to registered
    fallback families, never from Sora to Space Grotesk, so only a character both have is safe."""
    cover = None
    for name in BASE_FONTS:
        c = font_coverage(name)
        cover = c if cover is None else cover & c
    return cover or set()


def needed_characters(used=None):
    """The characters the fallback font must carry."""
    used = used if used is not None else used_characters()
    base = base_coverage()
    return {c for c in used if ord(c) not in base}


def main():
    used = used_characters()
    if "--needed" in sys.argv:
        print("".join(sorted(needed_characters(used))))
        return 0
    cover = base_coverage() | font_coverage(FALLBACK_FONT)
    missing = {c: where for c, where in used.items() if ord(c) not in cover}
    if not missing:
        print(f"OK — all {len(used)} non-ASCII characters in Ravilo's strings are in a bundled font.")
        return 0
    print(f"✗ {len(missing)} character(s) no bundled font has — the web app would draw a box:")
    for c in sorted(missing):
        where = missing[c]
        name = unicodedata.name(c, "?")
        print(f"  U+{ord(c):04X} {c}  {name}  ({len(where)}×)  e.g. {where[0]}")
    print("Draw an icon with components/Glyphs.kt, or rebuild the fallback: scripts/build-web-fallback-font.py")
    return 1


if __name__ == "__main__":
    sys.exit(main())
