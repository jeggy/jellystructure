# Phase R197 — Ravilo: picking a version in the sub-menu closes the picker (FR-RV-PICK1)

> Reported 2026-08-14: *"Currently when there are only 1 subtitles for a single language and I pick it
> the subtitles picker disappears (just like how it should). But when the language has 2 or more options
> and I pick one of those in the sub-menu, then it successfully picks it, but it doesn't close the
> picker. Let's fix this, so it also closes the picker."*

**Status:** Planned — dev-authored, not yet built. This is a deliberate reversal of an R195 design
decision, not an implementation defect.

## 1. What's happening, and why

R195 §D's navigation table specifies:

| Key | Level 1 | Level 2 |
|---|---|---|
| OK | one version → select · several → **enter level 2** | **select, stay open** |

and the shipped code implements exactly that (`PlayerScreen.kt:595-608`):

```kotlin
fun pickerSelect() {
    val group = pickerGroups.getOrNull(pickerIdx) ?: return
    if (pickerLevel == 0 && group.versions.size > 1) { pickerLevel = 1; …; return }
    if (pickerLevel == 0) pickerVersionIdx = 0
    choosePick()
    if (pickerLevel == 0) pickerOpen = false   // ← level 2 deliberately does not close
    wake()
}
```

`choosePick()`'s own doc records the split responsibility: it "never touches `pickerOpen`/`pickerLevel`
itself — callers decide whether picking should close the picker or leave it open."

The rationale was comparison: R195's implementation notes describe level 2 as
"OK-applies-and-stays-open" so a viewer can audition versions without reopening, chosen over a
riskier live-apply-on-focus-move design that §E's mockup had sketched.

In real use that reads as the picker failing to respond. The inconsistency is the problem: the same
key press on a language with one version dismisses the picker, and on a language with several leaves
it up, with the video still covered. Nothing on screen distinguishes "applied, still open for
comparison" from "didn't take".

## 2. Functional requirements

### FR-RV-PICK1-1 — OK on a level-2 version selects **and closes**

`pickerSelect()` closes the picker after `choosePick()` regardless of level. Concretely: drop the
`pickerLevel == 0` condition on `pickerOpen = false`, and reset `pickerLevel` to 0 so the next open
starts at the language list.

This applies identically to the D-pad Select path and to the touch path — `onTapVersion` already
routes through the same `pickerSelect()` (R195's own guarantee that TV and phone cannot diverge), so
no separate change is needed there, and none may be introduced.

Audio gets the same treatment as subtitles. The report is about subtitles, but R195 gave audio the
identical two-level structure, and leaving the two axes inconsistent would trade one surprise for
another.

Unchanged: OK on a **single**-version language still selects and closes (already correct); OK on a
multi-version language row still *enters* level 2 rather than selecting (the absence of an arrow is
R195 §A's whole cue for which one will happen, and that stays true).

### FR-RV-PICK1-2 — Back still returns to level 1

R195 §D's Back semantics are unaffected and must stay: Back in level 2 returns to level 1, Back in
level 1 closes. `pickerBack()` needs no change. This is what preserves the ability to look at a
language's versions and back out without changing anything.

### FR-RV-PICK1-3 — Amend R195 §D and its footer hint

- Update the §D table in `phase-R195-same-language-subtitle-picker.md` (level 2, OK → "select, close")
  with a dated note recording that this phase reversed it and why. Per this repo's convention, the
  superseded decision stays visible rather than being edited away.
- The level-2 footer hint string `player.picker_preview_hint` currently describes the
  OK-to-apply-and-keep-comparing flow. Re-word it in all three locales (en/da/fo) to match, or drop it
  if the row list is self-explanatory once selection dismisses — author's call, but it must not keep
  describing behaviour that no longer exists.
- R195's implementation notes discuss §E's rejected "moving down the list previews each one". That
  option is now foreclosed for good; note it as such rather than leaving it as an open future idea.

## 3. Non-goals

- Any live-apply-on-focus-move behaviour in level 2. Explicitly rejected by R195 and still rejected —
  every Up/Down would trigger a real track switch.
- Changing what level 1 does for either the single- or multi-version case.
- Any change to grouping, badges, flags, or the Off row.
- The remembered-choice defect — that is [R196](phase-R196-remembered-track-regression.md), a separate
  root cause in a different code path. `choosePick()`'s `persistChoice(...)` calls are correct and must
  not be touched here.

## 4. Verification

On a Pixel 9 Pro and on the TV build:

- A language with **one** subtitle version: OK selects and closes (unchanged).
- A language with **several**: OK enters level 2; OK on a version applies it **and closes**; the
  subtitle change is visible immediately.
- Reopening the picker lands on level 1 with the newly-chosen language's row marked active.
- Back from level 2 returns to level 1 and changes nothing.
- The same four checks on the Audio tab.
- Touch: tapping a level-2 row behaves identically to OK.
- Report any unexpected D-pad/focus behaviour noticed during the pass, per standing convention.
