#!/usr/bin/env python3
"""See scripts/check-i18n-spelling.sh for what this is and why. R287 FR-R287-4."""
import json, os, re, sys, unicodedata, collections

# R287 FR-R287-2 — the three places where two spellings of "the same letters" are both correct.
# Each is here because a real string breaks otherwise; nothing is excluded on a hunch.
#   browse.sort.*   `A–Á` / `A–Å` are the first and last letters of the alphabet, a range label.
#   {placeholder}   `Partar {a}–{b}` — `a` is a placeholder name, not a word.
#   after a `/`    `Innloggin/ur` — `ur` is a grammatical ending, not the word `úr`. Only the part
#                  AFTER the slash is exempt; the stem before it is an ordinary word.
EXCLUDED_KEYS = {"browse.sort.az", "browse.sort.za"}
PLACEHOLDER = re.compile(r"\{[^}]*\}")
WORD = re.compile(r"[^\W\d_]+", re.UNICODE)


def fold(w):
    """Strip every accent, so two spellings of one word collide."""
    bare = "".join(c for c in unicodedata.normalize("NFD", w) if unicodedata.category(c) != "Mn")
    return bare.replace("ð", "d").replace("Ð", "D").replace("æ", "ae").replace("ø", "o").lower()


def words_of(text):
    """Words that are really words: no placeholder contents, nothing glued to a slash."""
    masked = PLACEHOLDER.sub(lambda m: " " * len(m.group()), text)
    for m in WORD.finditer(masked):
        before = masked[m.start() - 1] if m.start() else ""
        after = masked[m.end()] if m.end() < len(masked) else ""
        # Only what follows the slash is the grammatical ending (`Innloggin/ur`). The stem before it
        # is an ordinary word and must still be checked — the first cut excluded both and hid it.
        if before == "/":
            continue
        yield m.group()


def spellings(path):
    seen = collections.defaultdict(collections.Counter)
    for key, value in json.load(open(path, encoding="utf-8")).items():
        if key == "_meta" or key in EXCLUDED_KEYS:
            continue
        text = value.get("text") if isinstance(value, dict) else value
        if not isinstance(text, str):
            continue
        for w in words_of(text):
            seen[fold(w)][w] += 1
    return seen


def main():
    root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "i18n")
    bad = []
    for fn in sorted(os.listdir(root)):
        if not fn.endswith(".json"):
            continue
        for _, forms in sorted(spellings(os.path.join(root, fn)).items()):
            if len({w.lower() for w in forms}) > 1:
                bad.append((fn, dict(forms)))
    for fn, forms in bad:
        shown = ", ".join(f'"{w}" x{n}' for w, n in sorted(forms.items()))
        print(f"  i18n/{fn}: {shown}")
    if bad:
        print()
        print(f"FAIL — {len(bad)} word(s) spelled more than one way in the same language.")
        print("These differ only by accents, so one of them is a typo. Pick the spelling the file")
        print("already uses elsewhere; if both are genuinely correct, add the key to EXCLUDED_KEYS")
        print("in this script with the reason written down.")
        return 1
    print("OK — every word is spelled one way in each language.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
