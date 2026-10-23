#!/usr/bin/env python3
"""verify-qr.py — read the round's QR the other way round, and check it.

Round `2026-10-23-tv-signin`. `ts-qr.js` writes a QR; this reads one back. It is the only check
available from this seat: there is no camera here and no QR decoder on the host, so without it
"the frames carry a real QR" would be an assertion rather than a fact.

It is a decoder, not a re-run of the encoder. What it establishes independently:

  * the format information passes its own BCH(15,5) check, in BOTH copies, and the two agree;
  * the error-correction level and mask index it declares are the ones the picture was masked with;
  * ALL 26 Reed-Solomon syndromes are zero, computed with this file's own GF(256) tables — a
    wrong generator polynomial in the encoder cannot survive this;
  * the byte-mode segment parses back to the exact string, which is what pins the data placement,
    the traversal order and the mask removal.

What it CANNOT establish: that a phone's camera finds and reads the printed picture. Module size,
quiet zone, contrast and the render's own scaling are physical questions. A PHYSICAL SCAN IS STILL
OWED, and the round's report says so rather than implying this script covered it.

    node -e "..." > matrix.txt   # see the Makefile-less one-liner in REPORT.md
    python3 -I verify-qr.py matrix.txt 'https://ravilo.example.net/tv?c=K7P4QX'
"""
import sys

SIZE = 29          # version 3
EC_CW = 26         # level M, one block
DATA_CW = 44


def gf_tables():
    """GF(256) with the QR primitive polynomial 0x11D. Written here, not imported."""
    exp = [0] * 512
    log = [0] * 256
    x = 1
    for i in range(255):
        exp[i] = x
        log[x] = i
        x <<= 1
        if x & 0x100:
            x ^= 0x11D
    for i in range(255, 512):
        exp[i] = exp[i - 255]
    return exp, log


EXP, LOG = gf_tables()


def gmul(a, b):
    return 0 if a == 0 or b == 0 else EXP[LOG[a] + LOG[b]]


def read_matrix(path):
    with open(path, "r", encoding="ascii") as fh:
        rows = [ln.strip() for ln in fh if ln.strip()]
    if len(rows) != SIZE or any(len(r) != SIZE for r in rows):
        raise SystemExit("verify-qr: expected %dx%d, got %d rows" % (SIZE, SIZE, len(rows)))
    return [[1 if ch == "1" else 0 for ch in r] for r in rows]


def reserved():
    """The function-pattern map, derived from the spec's geometry rather than from the encoder."""
    res = [[False] * SIZE for _ in range(SIZE)]

    def mark(r0, c0, r1, c1):
        for r in range(max(0, r0), min(SIZE, r1 + 1)):
            for c in range(max(0, c0), min(SIZE, c1 + 1)):
                res[r][c] = True

    # three finders with their separators
    mark(0, 0, 7, 7)
    mark(0, SIZE - 8, 7, SIZE - 1)
    mark(SIZE - 8, 0, SIZE - 1, 7)
    # timing patterns
    mark(6, 0, 6, SIZE - 1)
    mark(0, 6, SIZE - 1, 6)
    # the single version-3 alignment pattern, centred at (22, 22)
    mark(20, 20, 24, 24)
    # format information, both copies, plus the dark module at (4*3+9, 8)
    mark(8, 0, 8, 8)
    mark(0, 8, 8, 8)
    mark(8, SIZE - 8, 8, SIZE - 1)
    mark(SIZE - 8, 8, SIZE - 1, 8)
    return res


def bch_ok(fifteen):
    """A valid format word, after the 0x5412 unmask, is divisible by 0b10100110111."""
    v = fifteen
    for i in range(4, -1, -1):
        if v & (1 << (i + 10)):
            v ^= 0x537 << i
    return v == 0


def read_format(m):
    """Both copies, each unmasked and BCH-checked, and they must agree."""
    one = 0
    for i in range(0, 6):
        one |= m[8][i] << i
    one |= m[8][7] << 6
    one |= m[8][8] << 7
    one |= m[7][8] << 8
    for i in range(9, 15):
        one |= m[14 - i][8] << i

    # Bits 0-6 up the bottom-left column — seven modules, because (4*3+9, 8) is the dark module
    # and not a format bit — then bits 7-14 along the top-right row, eight modules.
    two = 0
    for i in range(0, 7):
        two |= m[SIZE - 1 - i][8] << i
    for i in range(7, 15):
        two |= m[8][SIZE - 8 + (i - 7)] << i

    out = []
    for name, raw in (("top-left", one), ("split", two)):
        v = raw ^ 0x5412
        if not bch_ok(v):
            raise SystemExit("verify-qr: format copy %s fails its BCH check (0x%04x)" % (name, raw))
        out.append(v >> 10)
    if out[0] != out[1]:
        raise SystemExit("verify-qr: the two format copies disagree: %r" % out)
    data = out[0]
    level = ["M", "L", "H", "Q"][data >> 3]      # 00=M 01=L 10=H 11=Q
    return level, data & 7


MASKS = [
    lambda i, j: (i + j) % 2 == 0,
    lambda i, j: i % 2 == 0,
    lambda i, j: j % 3 == 0,
    lambda i, j: (i + j) % 3 == 0,
    lambda i, j: (i // 2 + j // 3) % 2 == 0,
    lambda i, j: (i * j) % 2 + (i * j) % 3 == 0,
    lambda i, j: ((i * j) % 2 + (i * j) % 3) % 2 == 0,
    lambda i, j: ((i + j) % 2 + (i * j) % 3) % 2 == 0,
]


def codewords(m, res, mask):
    bits = []
    up = True
    col = SIZE - 1
    while col > 0:
        if col == 6:
            col -= 1
        for n in range(SIZE):
            row = SIZE - 1 - n if up else n
            for d in (0, 1):
                c = col - d
                if not res[row][c]:
                    bit = m[row][c]
                    if MASKS[mask](row, c):
                        bit ^= 1
                    bits.append(bit)
        up = not up
        col -= 2
    # Version 3 has 567 data modules and 70 codewords, so SEVEN REMAINDER BITS trail the last
    # codeword and are not part of it. Asserting 560 here is what failed the first run of this
    # script against a correct matrix — the encoder was right and the check was wrong.
    want = (DATA_CW + EC_CW) * 8
    if len(bits) != want + 7:
        raise SystemExit("verify-qr: walked %d data modules, expected %d (%d codeword bits + 7 remainder)"
                         % (len(bits), want + 7, want))
    if any(bits[want:]):
        raise SystemExit("verify-qr: the 7 remainder bits are not zero: %r" % bits[want:])
    bits = bits[:want]
    return [int("".join(str(b) for b in bits[i:i + 8]), 2) for i in range(0, len(bits), 8)]


def syndromes_zero(cw):
    """Evaluate the received polynomial at a^0 … a^25. A clean codeword gives 26 zeros."""
    bad = []
    for k in range(EC_CW):
        acc = 0
        for b in cw:
            acc = gmul(acc, EXP[k]) ^ b
        if acc:
            bad.append((k, acc))
    return bad


def parse(cw):
    bits = "".join(format(b, "08b") for b in cw[:DATA_CW])
    mode = int(bits[0:4], 2)
    if mode != 4:
        raise SystemExit("verify-qr: mode is %d, expected 4 (byte)" % mode)
    length = int(bits[4:12], 2)
    body = bits[12:12 + length * 8]
    if len(body) != length * 8:
        raise SystemExit("verify-qr: declared %d bytes but only %d bits follow" % (length, len(body)))
    return "".join(chr(int(body[i:i + 8], 2)) for i in range(0, len(body), 8))


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: verify-qr.py MATRIX.txt EXPECTED_STRING")
    path, expected = sys.argv[1], sys.argv[2]
    m = read_matrix(path)
    res = reserved()

    level, mask = read_format(m)
    print("format        : level %s, mask %d  (both copies agree, both pass BCH)" % (level, mask))
    if level != "M":
        raise SystemExit("verify-qr: level %s, the round encodes M" % level)

    cw = codewords(m, res, mask)
    bad = syndromes_zero(cw)
    if bad:
        raise SystemExit("verify-qr: %d of %d Reed-Solomon syndromes are non-zero: %r"
                         % (len(bad), EC_CW, bad[:4]))
    print("reed-solomon  : all %d syndromes zero" % EC_CW)

    got = parse(cw)
    print("decoded       : %r (%d bytes)" % (got, len(got)))
    if got != expected:
        raise SystemExit("verify-qr: MISMATCH\n  expected %r\n  got      %r" % (expected, got))
    print("match         : yes")
    print()
    print("NOT checked here: that a camera reads the printed picture. A physical scan is still owed.")


if __name__ == "__main__":
    main()
