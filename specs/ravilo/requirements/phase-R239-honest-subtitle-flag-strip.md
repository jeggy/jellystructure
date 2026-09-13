# Phase R239 — the flag strip must not say "no subtitles" when it means "no flag for those subtitles"

> Companion to **Phase 200**, from the same 2026-09-06 question about sidecar subtitles. 200 fixes
> *what languages reach the payload*; this fixes *what the strip does with them once they arrive*.

## Status
⚠ Partial. FR-R239-1/2/4/5/6 built and unit-tested 2026-09-10. FR-R239-3 (new flag assets) built
2026-09-12 for 9 of 12 codes (Serbian/Bulgarian/Indonesian/Malay/Slovenian/Estonian/Latvian/
Lithuanian/Filipino). **2026-09-13: Catalan added as a 10th** — Catalonia's flag (the Senyera) is a
single, uncontested regional flag, unlike Tamil/Telugu, which stay genuinely unmappable: reusing
India's flag (already Hindi's) for either would collapse three distinct languages onto one shown
flag via `AudioFlagStrip.kt`'s own by-flag dedup, reintroducing the exact undercount FR-R239-1 exists
to prevent, so they remain in the honest `+N`. New non-ISO code `ct` (`design/flags/4x3/ct.svg`, hand-
authored — flat two-color striped flags have no gradient-rendering risk, so ImageMagick rasterized it
directly to `flag_ct.png` without the Chromium detour brand-asset SVGs need) added to `flags.css` and
all three production `LANG_CC` tables (`AudioFlagStrip.kt`, admin `MediaDetail.kt`/`TrackEditor.kt`);
`check-lang-cc-sync.sh` still agrees (94 keys). **FR-R239-7's mockup sync partially done 2026-09-12,
extended 2026-09-13 for Catalan**: `design/ravilo/ravilo-app.js`, `ravilo-browse.js` and
`ravilo-player.js`'s three independently hand-written `LANG_CC` tables now carry the same 10 codes
(`ravilo-app.js` already had the honest-count logic from the earlier pass). **`Ravilo Mobile.html`
still has no audio/subtitle flag strip of any kind** — not a regression of this phase, a pre-existing
gap: the phone mockup is a self-contained file with no `flags.css` and no shared `tracksFor()`, so
building real parity there is a phone-side feature addition, out of this phase's
scope as a "fix". See STATUS.md for the full build summary.

## The finding

`AudioFlagStrip.kt:76-83`:

```kotlin
val mapped = audioLanguages.mapNotNull { lang -> LANG_CC[lang.lowercase()] }.distinct()
if (mapped.isEmpty()) return
val shown = mapped.take(FLAG_MAX)        // FLAG_MAX = 5
val extra = mapped.size - shown.size     // ← counts only the MAPPED leftovers
```

and `:129`:

```kotlin
private fun hasMappedFlag(languages: List<String>): Boolean =
    languages.any { LANG_CC.containsKey(it.lowercase()) }
```

Languages with no flag asset are dropped **before** anything is counted. Two consequences, both of
which make the strip assert something untrue rather than merely incomplete:

1. **A language with no flag is invisible and uncounted.** A title with 6 mapped and 15 unmapped
   subtitle languages renders five flags and `+1`. The viewer reads "six subtitle languages". There
   are twenty-one.
2. **A group with no mapped language disappears entirely.** `hasMappedFlag` false ⇒ `AudioSubtitleFlagLine`
   omits the whole SUBTITLES label (`:140`, `:150`). A title whose only subtitles are Indonesian and
   Serbian renders **identically to a title with no subtitles at all.** That is not an omission, it is
   a wrong answer to the one question the strip exists to answer.

### Coverage, measured

`LANG_CC` holds 73 codes (2- and 3-letter aliases of ~40 flags). The store holds **80 distinct
subtitle language codes**, of which **38 map to nothing**:

```
ind 268   may 229   bul 160   slv 136   tam 129   tel 129
est 125   lav 123   lit 122   srp  72   fil  71   cat  58   … (title counts)
```

**Serbian has no flag at all** — neither `sr` nor `srp` — while 211 movies carry a Serbian sidecar
subtitle. Bulgarian, Indonesian, Malay, the three Baltic languages and Slovenian are likewise absent.
Faroese, Danish and English are all mapped correctly; I checked those first, since they are the ones
this household actually reads.

Phase 200 makes this sharper: today many of those languages never reach the payload anyway. Once
sidecars are ingested and FR-200-4 unions the languages across a series' episodes, a typical series
detail goes from 1–9 languages to 15–25, most beyond `FLAG_MAX`, and the `+N` becomes the number the
viewer actually reads. It has to be true before it becomes load-bearing.

### The two tables are duplicated, and currently identical

`LANG_CC` exists twice — `AudioFlagStrip.kt` (Ravilo) and `MediaDetail.kt:3750` (admin). I diffed
them: **73 codes each, same keys, same flags, zero divergence.** That is luck, not structure. Any
language added to one must be added to the other, and nothing enforces it.

## Functional requirements

- **FR-R239-1 — `+N` counts every language, not every flag.** The overflow count is computed from the
  languages the item *has*, not from the subset that happens to have artwork. Five flags plus `+16` is
  honest; five flags plus `+1` is not.

- **FR-R239-2 — a group with subtitles always says so.** If `subtitleLanguages` is non-empty, the
  SUBTITLES label renders, even when nothing maps — as a bare count (`SUBTITLES +21`) with no flags.
  "Subtitles exist, we cannot draw them" and "there are no subtitles" must never look the same. Same
  rule for AUDIO.

- **FR-R239-3 — add the missing flags for what the library actually holds.** At minimum Serbian
  (`sr`/`srp`), Bulgarian, Indonesian, Malay, Slovenian, Estonian, Latvian, Lithuanian, Filipino and
  Catalan — the measured list above. Aliases in pairs (`sr` **and** `srp`): `srp` being absent while
  `sr` is present is exactly the kind of half-mapping that makes a language vanish for one title and
  appear for the next. **Tamil and Telugu built 2026-09-13, per this bullet's own instruction: no flag
  is missing for them, none exists that wouldn't be wrong** — see the Status section above.

- **FR-R239-4 — one table, or a test that the two agree.** Either share `LANG_CC` between the admin
  and Ravilo, or add a check that fails when they diverge. They agree today by luck; FR-R239-3 doubles
  every edit and is precisely when luck runs out.

- **FR-R239-5 — where a subtitle file lives never crosses into Ravilo.** Phase 200 adds `external` to
  `Track`; it stays server-side. The viewer is asking "is there Danish", not "is it muxed in". No
  badge, no split row, no second strip — the same render-never-compute discipline as R222.

- **FR-R239-6 — revisit `FLAG_MAX` once 200 lands, deliberately.** Five was chosen when a series
  showed one episode's languages. With the union it will routinely overflow. Either 5 stays and the
  honest `+N` carries the weight, or the cap rises — a decision to make with the real post-200 numbers
  on screen, not now. Recorded so it is a choice rather than a leftover.

- **FR-R239-7 — phone and TV alike, and the mockups too.** The strip is shared; `Ravilo Mobile.html`
  and the design mockups get the same treatment, so the design files don't drift back on the next sync.

## Non-goals

- **Ingest.** Phase 200 owns whether a language reaches the payload at all.
- **The picker.** R180/R195's two-level Audio & Subtitles picker reads live playback tracks, has its
  own text-fallback rule for unflagged languages, and is already correct. Untouched.
- **Per-track detail on the strip** — forced, SDH, counts per language. The strip is a glance; the
  picker is the detail. R195 settled that split.
- **Flag assets for languages the library does not hold.** FR-R239-3 is scoped to measured need, not
  to completing an atlas.

## Verification

`linuxX64Test`/`ravilo-ui` tests green. New cases: `+N` counts unmapped languages; a
wholly-unmapped non-empty list still renders its label; an empty list still renders nothing; alias
pairs resolve to the same flag. Visual check on a title with >5 subtitle languages and on one with
only unmapped languages — the second is the case that currently lies.

## Related

- **Phase 200** — the ingest half; the reason `+N` is about to matter.
- **R75 / R78 / R134** — the strip's origin and the merged one-line audio+subtitle layout.
- **R195** — the picker's own unflagged-language handling (text fallback, never a wrong flag); the
  precedent FR-R239-2 follows.
- **R222** — render-never-compute; the rule FR-R239-5 restates.
- **R221** — the last "+N on a capped strip" decision, for the `FLAG_MAX` discussion.
