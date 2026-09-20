#!/usr/bin/env python3
"""See scripts/check-ravilo-strings.sh for what this is and why. R279 FR-R279-14."""
import os, re, sys

# Positions that paint text. Matched against the source *before* the literal is read, so the reader
# below can handle "${str("key")}" — a quote inside an interpolation does not end the string.
TRIGGER = re.compile(
    r'\b(?:Text|BasicText|RaviloButton)\(\s*(?=")'
    r'|\b(?:label|text|title|subtitle|placeholder|contentDescription|hint|message|kicker'
    r'|caption|note|badge|episodeBadge|liveLine)\s*=\s*(?=")'
)
INTERP_NAMED = re.compile(r'\$[A-Za-z_][A-Za-z0-9_]*')

# R280 (FR-R280-6) — the shape the literal rule above cannot see. Thirteen stores drifted into
# putting `TvApiError.Http.message` — the HTTP response body, verbatim — on a state, and ten screens
# drew it, so a household with an expired token read `{"error":"Not logged in"}` on their TV. It is
# not a literal, so nothing flagged it for as long as it existed. A store has no language: it carries
# a cause (LoadErrorKind) and the screen says the sentence.
PAINT = (r'\b(?:Text|BasicText|RaviloButton)\(\s*'
         r'|\b(?:label|text|title|subtitle|placeholder|contentDescription|hint|message|kicker'
         r'|caption|note|badge|episodeBadge|liveLine)\s*=\s*')
RAW_MESSAGE = re.compile(
    '(?:' + PAINT + r')([A-Za-z_][A-Za-z0-9_]*(?:[?!]?\.[A-Za-z_][A-Za-z0-9_]*)*\.message)\b'
)
LETTER = re.compile(r'[^\W\d_]', re.UNICODE)

# Allowed in a rendering position, each for a stated reason:
#   Ravilo           the brand
#   OK               the key cap printed on the remote, beside "Skip Intro"
#   HLS/DIRECT PLAY  a delivery cue R180 FR-RV-ASP1-2 says should not be drawn at all. See the R279
#                    spec's findings — translating it would be the wrong fix, removing it is R180's.
#   10 s / −10s      a number and an SI unit, identical in every language we ship
#   camelCase        Compose's animate*AsState(label = "tileScale") — a debug name, not text
#   IMDb             a brand, like Ravilo
#   E · / E          what is left of "E${n} · ${title}" once the interpolations are removed
ALLOW = re.compile(
    r'^(?:Ravilo|IMDb|OK|HLS|DIRECT PLAY'
    r'|[-+\u2212]?\d+\s?s'
    r'|[a-z][a-zA-Z0-9]*'
    r'|E[\s\u00b7]*'
    r')$'
)
# Chrome the viewer never sees: the dev FPS overlay.
SKIP_FILES = {'FrameTracker.kt'}


def read_literal(src, i):
    """src[i] == '"'. Returns (text, next_index), treating ${...} as opaque (quotes inside it are
       part of the interpolation, not the end of the string)."""
    i += 1
    out, n = [], len(src)
    while i < n:
        c = src[i]
        if c == '\\':
            out.append(src[i:i + 2]); i += 2; continue
        if c == '"':
            return ''.join(out), i + 1
        if c == '$' and i + 1 < n and src[i + 1] == '{':
            depth, j = 1, i + 2
            while j < n and depth:
                if src[j] == '{': depth += 1
                elif src[j] == '}': depth -= 1
                elif src[j] == '"':                       # a nested literal inside ${ }
                    _, j = read_literal(src, j); continue
                j += 1
            out.append('\x00'); i = j; continue           # opaque placeholder
        if c == '\n':
            return ''.join(out), i                        # unterminated; give up on this one
        out.append(c); i += 1
    return ''.join(out), n


def violations():
    bad, raw = [], []
    for root in ('ravilo-ui/src', 'ravilo-cast/src', 'ravilo-screen/src', 'ravilo-receiver-core/src'):
        for dirpath, _, filenames in os.walk(root):
            parts = dirpath.split(os.sep)
            if 'build' in parts or 'commonTest' in parts or 'androidTest' in parts: continue
            for fn in sorted(filenames):
                if not fn.endswith('.kt') or fn.endswith('Test.kt') or fn in SKIP_FILES: continue
                p = os.path.join(dirpath, fn)
                src = open(p, encoding='utf-8').read()
                for m in TRIGGER.finditer(src):
                    lit, _ = read_literal(src, m.end())
                    line = src.count('\n', 0, m.end()) + 1
                    ctx = src[src.rfind('\n', 0, m.start()) + 1:m.start()].lstrip()
                    if ctx.startswith('//') or ctx.startswith('*'): continue
                    bare = INTERP_NAMED.sub('', lit.replace('\x00', '')).strip()
                    if not bare or not LETTER.search(bare): continue
                    if ALLOW.match(bare): continue
                    bad.append((p, line, lit))
                # R280 (FR-R280-6) — a store's raw failure text reaching a screen.
                for m in RAW_MESSAGE.finditer(src):
                    line = src.count('\n', 0, m.start()) + 1
                    ctx = src[src.rfind('\n', 0, m.start()) + 1:m.start()].lstrip()
                    if ctx.startswith('//') or ctx.startswith('*'): continue
                    raw.append((p, line, m.group(1)))
    return bad, raw


def main():
    bad, raw = violations()
    for p, line, lit in bad:
        print(f'  {p}:{line}  "{lit}"')
    if bad:
        print()
        print(f'FAIL — {len(bad)} literal string(s) in a text-rendering position.')
        print('Add the string to i18n/en.json (plus da/fo) and call str("your.key") instead.')
        print('If it genuinely is not prose (a glyph, a brand, a unit), add it to ALLOW in this script.')
    for p, line, expr in raw:
        print(f'  {p}:{line}  {expr}')
    if raw:
        print()
        print(f'FAIL — {len(raw)} raw failure message(s) in a text-rendering position.')
        print("That text is TvApiError.Http.message: the HTTP response body, verbatim, in no language.")
        print('Give the state a LoadErrorKind (loadErrorKindOf(cause)) and render LoadErrorState(kind).')
    if bad or raw:
        return 1
    print("OK — no hardcoded viewer-facing text in Ravilo's clients.")
    return 0


if __name__ == '__main__':
    sys.exit(main())
