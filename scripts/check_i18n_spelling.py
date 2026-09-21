#!/usr/bin/env python3
"""See scripts/check-i18n-spelling.sh for what this is and why. R287 FR-R287-4, R288 FR-R288-4/-5."""
import json, os, re, sys, unicodedata, collections

# R287 FR-R287-2 — the three places where two spellings of "the same letters" are both correct.
# Each is here because a real string breaks otherwise; nothing is excluded on a hunch.
#   browse.sort.*   `A–Á` / `A–Å` are the first and last letters of the alphabet, a range label.
#   {placeholder}   `Partar {a}–{b}` — `a` is a placeholder name, not a word.
#   after a `/`    `Innloggin/ur` — `ur` is a grammatical ending, not the word `úr`. Only the part
#                  AFTER the slash is exempt; the stem before it is an ordinary word.
EXCLUDED_KEYS = {"browse.sort.az", "browse.sort.za"}

# R288 — unit abbreviations are not words in any language, so they are exempt from folding against
# words that happen to look like them. `min` is minutes in all three files; Faroese `mín` is "my".
# Both are correct, they differ only by an accent, and neither is a typo for the other. Same shape as
# EXCLUDED_KEYS above: named here, with the reason, rather than inferred from a pattern.
UNIT_WORDS = {"min", "s", "m"}

# R288 — a word the language has a real word for, per the owner. Unlike everything else in this
# script this is word CHOICE, not spelling, so it is deliberately tiny, per-language, and every
# exception is a key with a reason. Danish is absent on purpose: `dette tv` is ordinary Danish.
#   fo: "In Faroese it's usually just Sjónvarp. TV is only used when an abbreviation is needed
#        because of small space." — owner, 2026-09-21
# `livetv.guide_kicker` was the one spot small enough to earn it (11sp, letter-spaced) and the owner
# chose `Beinleiðis` — dropping the noun rather than abbreviating it. So the allow-list is empty, and
# a future entry has to argue for itself in a diff.
DISCOURAGED = {
    "fo": {
        "tv": "use `sjónvarp` — `TV` only where small space genuinely forces an abbreviation",
        # R288 — owner: "the `urin` part on `telefon` is always incorrect, we should never use
        # that." `telefon` is feminine: telefonin / telefonina / telefonini. The file had
        # `telefonurin` twice against `telefonini` four times, and each string read fine alone, so
        # nothing could see it. Both masculine uses were removed by other edits; this keeps them out.
        "telefonurin": "`telefon` is feminine — telefonin / telefonina / telefonini",
        "telefonur": "`telefon` is feminine — telefonin / telefonina / telefonini",
    },
}
DISCOURAGED_OK = set()  # "<lang>:<key>" pairs that have earned the abbreviation

# The lexicon enforces accented orthography, which English does not have, and its digraph rule would
# be wrong about English words that legitimately carry `ae`/`oe` (`aeon`, `oestrogen`). en.json is
# the source language and an explicit non-goal of both R287 and R288.
NO_LEXICON = {"en"}
PLACEHOLDER = re.compile(r"\{[^}]*\}")
WORD = re.compile(r"[^\W\d_]+", re.UNICODE)
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "i18n")


def fold(w):
    """Strip every accent, so two spellings of one word collide.

    R288 FR-R288-4: lowercase FIRST. The first cut lowercased last, so `ø`->`o` and `æ`->`ae` never
    reached an uppercase `Ø` or `Æ`: fold("Oll") was "oll" but fold("Øll") was "øll", and the two
    never collided. That is how `screens.all` sat on "Öll tíni sjónvørp" — a letter Faroese does not
    have — one line from two keys spelling it "Øll", through this very check.
    """
    lowered = w.lower()
    bare = "".join(
        c for c in unicodedata.normalize("NFD", lowered) if unicodedata.category(c) != "Mn"
    )
    return bare.replace("ð", "d").replace("æ", "ae").replace("ø", "o")


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
            if w.lower() in UNIT_WORDS:
                continue
            seen[fold(w)][w] += 1
    return seen


DIGRAPHS = ("ae", "oe", "aa")


def lexicon_path(code):
    return os.path.join(ROOT, "lexicon", f"{code}.txt")


def load_lexicon(code):
    """Every word form this language legitimately uses, reviewed once by a speaker.

    R288 FR-R288-5. R287 could only see a word spelled two ways in one file; a word stripped
    EVERYWHERE (`Mal`, `Latid`, `Sog`) was perfectly self-consistent and sailed through. R287 tried a
    stem-matching heuristic for that class and rejected it, rightly: it flagged `Heim` and `samband`,
    which are correctly unaccented, because a heuristic has no ground truth to appeal to.

    This is the ground truth. `heim` is in here and passes; `mal` is not, and `mál` is.
    """
    path = lexicon_path(code)
    if not os.path.exists(path):
        return None
    words = set()
    for line in open(path, encoding="utf-8"):
        line = line.split("#", 1)[0].strip()
        if line:
            words.add(line.lower())
    return words


def collect(path):
    """Every word form the file uses, lowercased, with the keys it came from."""
    seen = collections.defaultdict(set)
    for key, value in json.load(open(path, encoding="utf-8")).items():
        if key == "_meta" or key in EXCLUDED_KEYS:
            continue
        text = value.get("text") if isinstance(value, dict) else value
        if not isinstance(text, str):
            continue
        for w in words_of(text):
            if w.lower() in UNIT_WORDS:
                continue
            seen[w.lower()].add(key)
    return seen


def check_discouraged(code, path):
    """One word the owner has a Faroese word for. Word choice, not spelling — see DISCOURAGED."""
    rules = DISCOURAGED.get(code)
    if not rules:
        return []
    problems = []
    for key, value in json.load(open(path, encoding="utf-8")).items():
        if key == "_meta" or f"{code}:{key}" in DISCOURAGED_OK:
            continue
        text = value.get("text") if isinstance(value, dict) else value
        if not isinstance(text, str):
            continue
        for w in words_of(text):
            why = rules.get(w.lower())
            if why:
                problems.append((w, why, [key], "choice"))
    return problems


def check_against_lexicon(code, used):
    """Two failures a self-consistency check structurally cannot see."""
    lex = load_lexicon(code)
    if lex is None:
        return []
    by_fold = collections.defaultdict(set)
    for w in lex:
        by_fold[fold(w)].add(w)
    problems = []
    for w, keys in sorted(used.items()):
        if w in lex:
            continue
        twins = by_fold.get(fold(w), set())
        if twins:
            # A variant of a word this language already has. `Mal` against `mál`, `Sog` against `Søg`.
            problems.append((w, "should be " + " / ".join(sorted(twins)), sorted(keys), "spelling"))
        elif any(d in w for d in DIGRAPHS):
            # `Naest` for `Næst`. A new word may legitimately contain one — add it to the lexicon,
            # with its reason, so the next reader can see the decision was made on purpose.
            problems.append((w, "digraph spelling — use the real letter", sorted(keys), "spelling"))
    return problems


def write_lexicon(code, used):
    path = lexicon_path(code)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    header = [
        f"# Every word form i18n/{code}.json legitimately uses. R288 FR-R288-5.",
        "#",
        "# This is ground truth, not a dictionary: a word is in here because a speaker reviewed the",
        "# string it appears in. The check fails when a string uses a word that is NOT in here but",
        "# whose accent-folded form IS -- `Mal` against `mál` -- and when a new word carries an",
        "# `ae`/`oe`/`aa` digraph instead of the real letter.",
        "#",
        "# Adding a line is a deliberate act. It belongs in the same commit as the string that needs",
        "# it, so a reviewer sees the decision in the diff. Regenerate with:",
        "#     scripts/check-i18n-spelling.sh --update-lexicon",
        "#",
        "# `Ambætaraadressa` keeps its `aa`: it is a word join (Ambætara + adressa) and Faroese has no",
        "# `å` for an `aa` rule to produce. R287 FR-R287-2 found that; now the check can read it.",
        "",
    ]
    body = sorted(used)
    open(path, "w", encoding="utf-8").write("\n".join(header + body) + "\n")
    return path, len(body)


def main():
    update = "--update-lexicon" in sys.argv
    failed = False
    for fn in sorted(os.listdir(ROOT)):
        if not fn.endswith(".json"):
            continue
        code = fn[:-5]
        path = os.path.join(ROOT, fn)

        # R287 — one word, one spelling, within this file.
        bad = []
        for _, forms in sorted(spellings(path).items()):
            if len({w.lower() for w in forms}) > 1:
                bad.append(dict(forms))
        for forms in bad:
            shown = ", ".join(f'"{w}" x{n}' for w, n in sorted(forms.items()))
            print(f"  i18n/{fn}: {shown}")
        if bad:
            print()
            print(f"FAIL — {len(bad)} word(s) spelled more than one way in i18n/{fn}.")
            print("These differ only by accents, so one of them is a typo. Pick the spelling the file")
            print("already uses elsewhere; if both are genuinely correct, add the key to EXCLUDED_KEYS")
            print("in this script with the reason written down.")
            failed = True
            continue

        if code in NO_LEXICON:
            continue

        used = collect(path)
        if update:
            written, n = write_lexicon(code, used)
            print(f"  wrote {os.path.relpath(written, os.path.join(ROOT, '..'))} — {n} word forms")
            continue

        # R288 — one word, the RIGHT spelling, judged against the reviewed lexicon.
        problems = check_against_lexicon(code, used) + check_discouraged(code, path)
        for w, why, keys, _kind in problems:
            shown = ", ".join(keys[:3]) + ("" if len(keys) <= 3 else f", +{len(keys) - 3} more")
            print(f'  i18n/{fn}: "{w}" — {why}  ({shown})')
        if problems:
            kinds = {k for *_, k in problems}
            print()
            print(f"FAIL — {len(problems)} word(s) in i18n/{fn}.")
            if "spelling" in kinds:
                print("Spelling: use the real letters — `Næst`, not `Naest`; `Mál`, not `Mal`. If the word is")
                print("genuinely new and genuinely spelled that way, add it to i18n/lexicon/%s.txt in this" % code)
                print("same commit, so the decision is visible to whoever reads the diff.")
            if "choice" in kinds:
                print("Word choice: each line above says what this language uses instead. If one is genuinely")
                print("right in its context, add \"%s:<key>\" to DISCOURAGED_OK in this script with the" % code)
                print("reason — these are owner decisions, not guesses, so an exception needs one too.")
            failed = True

    if failed:
        return 1
    print("OK — every word is spelled one way, and the way the lexicon says." if not update else "OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
