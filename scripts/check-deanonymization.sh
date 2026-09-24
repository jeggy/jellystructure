#!/usr/bin/env bash
# Regression fence for real values scrubbed from this repository and its whole git history:
# the household's domain and IP addresses, real tracker names and an announce passkey, and the
# titles actually in the household's library (each replaced by an invented one).
#
# A design-tool sync has reintroduced scrubbed values from its own stale mirror before (the 13th
# incident, 2026-09-16, brought back every infra value across ~20 files), so this runs after every
# sync, beside check-mobile-css.sh / check-css-scoping.sh.
#
# The values themselves are NOT listed here — an earlier version of this script named every one of
# them in plain text, which published exactly what it was meant to keep out. Each banned value is
# stored as the first 20 hex chars of sha256(its word sequence), where a word sequence is the value's
# \w+ runs joined by one space ("some.host.net" hashes as "some host net"). Every tracked text file is
# cut into the same words, and every run of 1..MAX_WORDS consecutive words is hashed and looked up.
#
# ADD A HASH whenever a new real value is scrubbed:
#   python3 -c 'import re,hashlib,sys; print(hashlib.sha256(" ".join(re.findall(r"\w+",sys.argv[1])).encode()).hexdigest()[:20])' 'the value'
set -euo pipefail
cd "$(dirname "$0")/.."

python3 - <<'PY'
import hashlib, re, subprocess, sys

MAX_WORDS = 14
BANNED = set("""
02540577ff8fb6216401 0461282e2eaffa7fe836 04c3326b48866de84dc4 09283274db21a86ef445
0b7654b24e5c12866e0f 0c2a6740dc0c4f0b350b 117363b99ebea5a4dec5 122afc7632e8dd9b2f76
12dac494fae6232f11b7 14572e085d9cf50f5daa 165571c81b5ab83d33da 18f46d10ab0e57a26a7d
198a1cbcea1abf772370 1a089b22b9f9c93ed8e8 1a1a65a2fb60e05e9422 1d443a38c3836412f834
1faead6e413f8b8adf59 200929f2def34296b622 20e98c0e98ffd2f9444b 2288636bc31f7e6ff201
239352b0c400e02b29b3 23cf778a5865f3227d09 24c33b79dee89a9e0c37 25c5ba3fb4e5779f38a1
271988d118d315123fbb 2756f7b25d7637dcadc0 27e228b2619110389869 28ccf0cced18fc149753
2a737ccbb61724337179 2acade259e3937ea2f1e 2b4863092f59d556d387 2c076c27dde818a2e310
2c4774ee92caec3e5728 2dcfdfb1ad1b98507b3d 2ebdb93ceb3ed3ab6a6e 2f4d9b8c3e47b7f00c87
305b7d8599ed9a56cbfe 325b854e55da820bd2b6 33765a0623a2ecb9f637 37d1be0a6e1663f06d98
3af22438e36d10d15f44 3bcfb6836c217586b9f5 3d69f75ddd6beb75d2de 3d976d35775e14d52153
3e65c29ef0cb96f86e8e 407ec86a80ca926ad7b2 412a76dca7817504ab28 41358eae978848716c89
427a213cb610bc808809 47bc4555a1b8782fcd8b 48bda1b46aeb5375f4fd 4af2c823df14cdecad01
4c9f4a591e6e9dab2704 4d33b110b03ad7f20a58 4fb6aa88d8e3c8c137e0 500b4315735a0dbfd0bd
5261ed4e38a9c8063cdd 532ddc6615426801e850 543f62ae559749443d33 546f29bd9e9294663279
557f28e4791128406031 56c78bb5dadaa0a8e137 576e8393fa51c2902cbc 5840428a62abb9e48297
586ebceccd66697071ab 5aef7be63f285709b262 5b12884ef28589c1984f 5cf8967f3ec5ebc17466
6044764e4612e5ad4f46 6247312a6806075a11af 628c93ffb5ac5655a4d7 6572f04c046bec09a7b4
660de3de1ac227115b43 6a0411b6df0b98b4a816 6b00f3f1af811946dd93 6baad51c24ee230c931f
6c9b22c0b66c6ad84e5c 6dde39a7df765120aaef 729f531bc46f706b6413 761755cd15b9d7939266
79556396296234c7edfd 7a19074cbb518dec79fc 7c99404549e4739e81d5 80349f1cfe3c555b0a4e
8076d04ab6744f509299 8248d9a5762fb0779dab 840a1a282000ed5d9f9f 8597a83bb9b373dcdfb5
86ddc611611753e8d559 877956f2af06c36a7857 878b24497dd8395d8dfe 87983ed6368d0ac563b3
88002fa45e3c9afb81e6 8bdd01e66223741b9444 90d254abb82b914e308c 90e47c23532f58f25974
9259a128c6f25569f890 95c73b946895fff95302 9944a5fe885b321955e9
9cb66c6ec44b47df82b1 9d7359eecbad5dc4ebd6 9eada5dc39c79defc6dc 9ebbe172fce4cef697f5
a6027bbf215c54daebdd a6d8d3b70a428004fb61 a8bb8f1f60e00ecc6529 abdf90725e94dad58968
ac129d99fecdf5d0eb5a acde425704b5b8debc18 acdf423a60cfe6797afb b035abe15e612cb705f6
b12bc12e4203cf04dd30 b1498c47244fd1c8577c b50933052db14319414e b599ae15042dbb622c61
b7d74f11e62707d00f20 b85ada4b214256f0a6fc b9659b6f1308e52c7695 ba8d4bea7d6587a67ecd
bb72cc0914580a231309 be16d5e99c37a7ea8673 c2ad2455247b45aa9d2d c33d8e77938daf7b53b5
c3bdabd65e5607ea523f c42d0bad39d1ce39e89e c9175667c585cb2a53e5 ca357bde7959cef95f66
caa229bbd0bcca9d653b cc5bec28bf557fb750b5 ccef5ea1a013cc86392e ce103f9290bb9c429a12
d0a4f73468a02b189a8d d12a52a456b07855f4e3 d1b15d855a06b6e5d7c9 d33b966951969b1e02f5
d349076e8da13ca79841 d369cedb105e225f6b75 d4c3baffb32b4f81a6e1 d5090d5a485ee099108c
d5b880086c632f59882b d6e9b032ef1922051998 d7910056a34e616953bf da8623f7fd72e5fcc49a
dd0246f0f154d2578b07 dd275e3fbd5d5af77930 de703f1fe9ae64015b1d deab03fe91f520d7b288
dfa4f91901a821afae3b dfbafe58522dca7e36eb e105601cd0304e4e6c83 e1b46393c127d03bc75f
e2caf830e87e47c7ba6a ee2e1307ab1b7fe6a381 ef18b21bc6e38c7ea585 efa1f0fef81de7536c5f
f037f38881bd9bacb43b f1dd07991029d5fc97b9 f5c4ca5d22052e1875b1 f894d864ebbed3485195
fbbe2f24e692ace7470c fcf5a488cc8b7ede23f8 ff45d0a515b093cd2031 9873beaef82ee5701c26
""".split())

SKIP_SUFFIX = ('.min.js', '.lock', '.map', '.svg', '.png', '.jpg', '.jpeg', '.webp', '.gif', '.ico',
               '.ttf', '.woff', '.woff2', '.jar', '.apk', '.wgt', '.mp4', '.webm')
SKIP_PARTS = ('/vendor/', 'node_modules/', 'design/flags/')
WORD = re.compile(r'\w+')

files = subprocess.check_output(['git', 'ls-files', '-z']).decode().split('\0')
hits = {}
for f in files:
    if not f or f.endswith(SKIP_SUFFIX) or any(p in f for p in SKIP_PARTS):
        continue
    try:
        data = open(f, 'rb').read()
    except OSError:
        continue
    if b'\0' in data[:8192]:
        continue
    for lineno, line in enumerate(data.decode('utf-8', 'replace').splitlines(), 1):
        words = WORD.findall(line)
        for i in range(len(words)):
            for j in range(i + 1, min(i + MAX_WORDS, len(words)) + 1):
                h = hashlib.sha256(' '.join(words[i:j]).encode()).hexdigest()[:20]
                if h in BANNED:
                    hits.setdefault(f, []).append(lineno)

if not hits:
    print('OK — no scrubbed real values found.')
    sys.exit(0)
print('LEAKED — a value scrubbed from history is back:')
for f, lines in sorted(hits.items()):
    print(f'  {f}: line(s) {", ".join(map(str, sorted(set(lines))))}')
print()
print('A design sync has very likely reintroduced it from a stale mirror. Replace it with its invented')
print('stand-in (see git log -S on the stand-in, or ask the owner) — never commit the real value.')
sys.exit(1)
PY
