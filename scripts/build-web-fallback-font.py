#!/usr/bin/env python3
"""R315 (FR-R315-2/3/4) — build Ravilo's web fallback font.

Compose on the web (Compose Multiplatform 1.9.3) draws text only from the fonts the app bundles. This
builds ONE font carrying exactly the characters the app's strings use that Sora and Space Grotesk do not
both have — the symbols translators write, and the native names of every language in
LanguageIdentity.kt — subset from Noto, with each script's shaping tables (GSUB/GPOS) kept so Arabic
joins and Indic conjuncts form. The web app registers it as a fallback family before the first frame.

    pip install fonttools        # only to REGENERATE; the app's build never runs this
    python3 scripts/build-web-fallback-font.py

Sources are downloaded once into $NOTO_CACHE (default ~/.cache/ravilo-noto), never into the repository.
The character set comes from scripts/web_glyphs.py, the same scan scripts/check-web-glyphs.sh fails on,
so after a new language name or a translator's arrow: rerun this, commit the font, and the check passes.
Noto is under the SIL Open Font License 1.1, shipped beside the font:
ravilo-ui/src/wasmJsMain/composeResources/files/licenses/noto-OFL.txt.
"""
import io
import os
import sys
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import web_glyphs  # noqa: E402

from fontTools import subset  # noqa: E402
from fontTools.merge import Merger  # noqa: E402
from fontTools.ttLib import TTFont  # noqa: E402
from fontTools.ttLib.scaleUpem import scale_upem  # noqa: E402
from fontTools.varLib import instancer  # noqa: E402

NOTO = "https://github.com/notofonts/notofonts.github.io/raw/main/fonts/{0}/hinted/ttf/{0}-Regular.ttf"
GF = "https://github.com/google/fonts/raw/main/ofl/{0}/{1}%5Bwght%5D.ttf"

# In order: the first source that has a character supplies it.
SOURCES = [
    ("NotoSans", NOTO.format("NotoSans")),                    # Latin extras, Greek, Cyrillic, arrows
    ("NotoSansSymbols2", NOTO.format("NotoSansSymbols2")),    # ✓ ★ ▴ ▾ ◂ ▸ ▷
    ("NotoSansMath", NOTO.format("NotoSansMath")),            # ↵
    ("NotoSansSymbols", NOTO.format("NotoSansSymbols")),      # ⌃ (the Mac's Control key, in the shortcuts list)
    ("NotoSansArabic", NOTO.format("NotoSansArabic")),
    ("NotoSansHebrew", NOTO.format("NotoSansHebrew")),
    ("NotoSansSyriac", NOTO.format("NotoSansSyriac")),
    ("NotoSansArmenian", NOTO.format("NotoSansArmenian")),
    ("NotoSansGeorgian", NOTO.format("NotoSansGeorgian")),
    ("NotoSansDevanagari", NOTO.format("NotoSansDevanagari")),
    ("NotoSansBengali", NOTO.format("NotoSansBengali")),
    ("NotoSansGurmukhi", NOTO.format("NotoSansGurmukhi")),
    ("NotoSansGujarati", NOTO.format("NotoSansGujarati")),
    ("NotoSansTamil", NOTO.format("NotoSansTamil")),
    ("NotoSansTelugu", NOTO.format("NotoSansTelugu")),
    ("NotoSansKannada", NOTO.format("NotoSansKannada")),
    ("NotoSansMalayalam", NOTO.format("NotoSansMalayalam")),
    ("NotoSansSinhala", NOTO.format("NotoSansSinhala")),
    ("NotoSansThai", NOTO.format("NotoSansThai")),
    ("NotoSansMyanmar", NOTO.format("NotoSansMyanmar")),
    ("NotoSansKhmer", NOTO.format("NotoSansKhmer")),
    ("NotoSerifTibetan", NOTO.format("NotoSerifTibetan")),    # Noto has no sans Tibetan
    ("NotoSansSC", GF.format("notosanssc", "NotoSansSC")),    # 中文 日本語 ＋ (variable: instanced at 400)
    ("NotoSansKR", GF.format("notosanskr", "NotoSansKR")),    # 한국어
]

OUT = os.path.join(web_glyphs.FALLBACK_DIR, web_glyphs.FALLBACK_FONT)
CACHE = os.environ.get("NOTO_CACHE", os.path.expanduser("~/.cache/ravilo-noto"))
FAMILY = "Ravilo Web Fallback"


def fetch(name, url):
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, name + ".ttf")
    if not os.path.exists(path):
        print(f"  downloading {name}…")
        with urllib.request.urlopen(url, timeout=120) as r:
            data = r.read()
        with open(path + ".part", "wb") as f:
            f.write(data)
        os.replace(path + ".part", path)
    return path


def static(font):
    if "fvar" in font:
        font = instancer.instantiateVariableFont(font, {a.axisTag: (400 if a.axisTag == "wght" else a.defaultValue) for a in font["fvar"].axes})
    return font


def subset_to(font, chars):
    opts = subset.Options()
    opts.layout_features = ["*"]      # keep every shaping feature: joins, conjuncts, marks
    opts.name_IDs = ["*"]
    opts.hinting = False              # hints cost bytes and Skia does not use them for this
    opts.notdef_outline = True
    opts.glyph_names = False
    # Tables fontTools cannot merge (MATH) or that a fallback does not need; the shaping tables stay.
    opts.drop_tables += ["DSIG", "vhea", "vmtx", "VORG", "MATH", "BASE", "STAT", "meta", "JSTF"]
    s = subset.Subsetter(opts)
    s.populate(unicodes=[ord(c) for c in chars])
    s.subset(font)
    return font


def main():
    needed = web_glyphs.needed_characters()
    print(f"{len(needed)} characters to carry")
    remaining = set(needed)
    parts = []
    for name, url in SOURCES:
        if not remaining:
            break
        font = TTFont(fetch(name, url), lazy=False)
        cmap = font.getBestCmap()
        take = {c for c in remaining if ord(c) in cmap}
        if not take:
            continue
        font = static(font)
        if font["head"].unitsPerEm != 1000:
            scale_upem(font, 1000)
        subset_to(font, take)
        buf = io.BytesIO()
        font.save(buf)
        buf.seek(0)
        parts.append((name, buf, take))
        remaining -= take
        print(f"  {name}: {''.join(sorted(take))}")
    if remaining:
        print("✗ no source has: " + " ".join(f"U+{ord(c):04X} {c}" for c in sorted(remaining)))
        return 1

    merged = Merger().merge([p[1] for p in parts])
    names = merged["name"]
    for rec in list(names.names):
        if rec.nameID in (1, 4, 16):
            names.setName(FAMILY, rec.nameID, rec.platformID, rec.platEncID, rec.langID)
        elif rec.nameID == 6:
            names.setName(FAMILY.replace(" ", ""), rec.nameID, rec.platformID, rec.platEncID, rec.langID)
    merged.save(OUT)
    got = web_glyphs.font_coverage(web_glyphs.FALLBACK_FONT)
    lost = [c for c in needed if ord(c) not in got]
    print(f"wrote {os.path.relpath(OUT, web_glyphs.ROOT)} — {os.path.getsize(OUT) // 1024} KB, "
          f"{len(needed) - len(lost)}/{len(needed)} characters from {len(parts)} sources")
    if lost:
        print("✗ lost in the merge: " + " ".join(lost))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
