# Phase R288 — Faroese says what it means

> R287 fixed the spellings the file could prove. Its own STATUS row names what it could not:
> *"Five words are still wrong and were deliberately not guessed at — `Mal`, `Spaling`,
> `sjalvvirkandi`, `Latid`, `Innritad/ur` are stripped everywhere, so the file offers no evidence
> and the checker cannot see them (it detects inconsistency, not wrongness). Also spotted, not a
> spelling issue: `bogvahandan` does not look like a word at all."*
>
> Owner, this session: *"get Faroese fully stable and ready"*, and — on seeing the digraph words —
> *"we should never use terms like `Naest`, we should use real letters like `Næst`."*

## Status

`✓ Built` — design-authored and built 2026-09-21 with the owner answering every terminology
question. Not dev-reviewed, **not yet seen on a device**. **168 strings changed in `i18n/fo.json`
and 7 in `i18n/da.json`; no key added, removed or reordered in any language, and `en.json` is
untouched.** `generateRaviloStrings` and `checkRaviloStrings` both succeed against the result.

### Two things the design met on the way, recorded rather than quietly absorbed

- **`min` is not `mín`.** Fixing `nav.my_list` from `Mitt listi` to `Mín listi` (a gender error —
  `listi` is masculine) put the word `mín` into the file for the first time, where it immediately
  collided with `min`, the **minutes abbreviation** in `up.runtime_min`, `fd.min_left` and
  `action.resume_mins_left`. Both are correct and neither is a typo for the other — the same shape
  as R287's `A–Á`. `UNIT_WORDS = {min, s, m}` is exempt, named in the script with the reason. A unit
  abbreviation is not a word in any language, which is why it is safe to exempt in all of them.
- **English gets no lexicon.** The digraph rule would be wrong about English words that legitimately
  carry `ae`/`oe` (`aeon`, `oestrogen`), and English has no accented orthography for the fold half to
  protect. `NO_LEXICON = {en}`; `en.json` was already a non-goal of both R287 and R288.

### Verified

The regression tests of Acceptance 7, each run against the finished files:

| reverted | caught by | says |
|---|---|---|
| `Mál` → `Mal` (one of two) | R287's within-file fold | `"Mal" x1, "mál" x1` |
| `Mál` → `Mal` (**both**, so the file is self-consistent and wrong) | R288's lexicon | `"mal" — should be mál` |
| `Næst` → `Naest` | R288's digraph rule | `"naest" — should be næst` |
| `Øll` → `Öll` | R287's check, once FR-R288-4 fixed `fold()` | `"Öll" x1, "Øll" x2` |
| `på` → `pa` (Danish) | R287's within-file fold | `"På" x1, "pa" x1, "på" x31` |
| a genuinely **new**, correctly-spelled Faroese word | nothing — it passes | `OK` |

The second row is the whole point of the phase: that is the case R287 named, could not see, and
said so about.

## Context

R287 was a *consistency* repair and was scoped as one deliberately: the file spelled a word two
ways, so the file was its own authority and no Faroese had to be guessed at. That principle has a
hard edge, and R287 hit it and stopped at it honestly. A word that is stripped **everywhere** is
spelled consistently, so `check-i18n-spelling.sh` passes it; and a word that is simply the **wrong
word** is spelled perfectly.

Both edges hide real defects. `cast.stop` — the button that stops casting — reads **"Stovna
casting"**, and *stovna* means *found* or *establish*. The stop button says start, in valid JSON,
with every test green.

This phase is therefore the thing R287 declared out of scope: **translation quality**, decided by
the owner, who speaks the language. Every terminology choice below was asked and answered rather
than inferred.

### Why the checker could not see any of this, and what fixes that

R287 tried a stem-matching heuristic to find the stripped-everywhere class and rejected it, because
it flagged `Heim`, `samband` and `Hjem` — words that are correctly unaccented. That rejection was
right: a heuristic that cannot tell *correctly unaccented* from *wrongly unaccented* has no ground
truth to appeal to.

The missing ground truth is a **lexicon**: a reviewed list of the word forms each language actually
uses. `Heim` is in it and is correct; `Mal` is not in it and `mál` is. FR-R288-5 adds one, built
from the corrected files and committed, so the same defect cannot return silently.

Two defects found here also argue that the existing checker has a hole of its own, not just a blind
spot — see FR-R288-4.

## Non-goals

- **English.** `en.json` is untouched, including its `...` ellipses.
- **Adding or removing keys.** The key set is identical before and after, in all three languages.
- **Re-litigating R287.** Its exclusions (`browse.sort.az`/`za`, `{a}`, the part after a `/`) all
  still hold and are all still excluded.

## Functional requirements

### FR-R288-1 — one word per thing, chosen by the owner

Nine terms were each drawn two to five ways. The owner picked one of each; every other string
follows it.

| Thing | Was | Now |
|---|---|---|
| episode | `attrid` · `evni` · `partar` · `rað` · `táttur` | **`partur`** |
| season | `Leikrit` (*a stage play*) | **`Sesong`** |
| collections | `Savn` (*collided with Library*) | **`Samlingar`** |
| server | `servari` · `ambætari` | **`ambætari`** |
| profile | `vangi` · `vangamynd` (*the photo*) | **`vangi`** |
| screen | `skíggi` · `skermur` | **`skermur`** |
| device | `tól` · `telda` (*a computer*) · `eind` | **`eind`** |
| *see* (imperative) | `Síggj` · `Sí` · `Sig` (*say*) | **`Sí`** |
| title | `titul` · `heiti` | **`heiti`** (8 uses to 3; the file's own majority) |

`Savn` keeps Library, which is why Collections is the side that moved.

### FR-R288-2 — the words that were simply wrong

Not misspellings. Each says something other than what the English says.

- `cast.stop` — **`Stovna casting`** → `Steðga casting`. *Stovna* is *found/establish*: the stop
  button said start. The most serious string in the file.
- `livetv.watch_live` — `Sig live` → `Sí beinleiðis`. *Sig* is *say*; `beinleiðis` is the word
  `livetv.on_now` and `livetv.guide_kicker` already use for *live*.
- `profile.switch` — `Bryt profil` → `Skift vanga`. *Bryt* is *break*.
- `profile.who` — `Hvor er at síggja?` → `Hvør hyggur?`. *Hvor* is not a Faroese word, and the
  sentence asked *who is to be seen*.
- `action.remove_list` — `Fjern úr minum lista` → `Tak úr mínum lista`. *Fjern* is Danish.
- `request.search_hint` — `at bíða um` → `at biðja um`. *Bíða* is *wait for*; the tab requests.
  `request.waiting_for` keeps `Bíðar eftir`, where waiting is what is meant.
- `seg.coming` — `Comandi skjótt` → `Komandi skjótt`. `nav.upcoming` already says `Komandi`.
- `section.channels_sub` — `Vadast eftir flokki` → `Kanna eftir flokki`.
- `action.more_info` — `Meiri abending` → `Meiri kunning`. *Ábending* is a *hint*.
- `player.last_used` — `Nýtt seinast` (*newly last*) → `Brúkt seinast`.
- `browse.maturity.hint` — `OK gongur` (*OK walks*) → `OK liðugt`.
- `up.nothing` — `Einki alagt` (*nothing imposed*) → `Einki ætlað`.

### FR-R288-3 — the two strings that are not words at all

- `up.subtitle` and `up.foot_upcoming` both contain **`bogvahandan tínar`**, where *your library*
  belongs. The file says `savnið` correctly in four other places. → `savnið títt`.
- `screens.offline` reads **`Ofrlinu`**. → `Ótengt`.

### FR-R288-4 — the checker's own hole

`fold()` in `scripts/check_i18n_spelling.py` lowercases **after** substituting `ø`→`o` and
`æ`→`ae`, so an uppercase `Ø` or `Æ` never folds: `fold("Øll")` is `"øll"` while `fold("Öll")` is
`"oll"`. That is why `screens.all` has sat on `Öll tíni sjónvørp` — a letter Faroese does not
have — one line away from two keys spelling it `Øll`, through a check whose whole purpose is that
collision, and through R287, which fixed `öllum`→`øllum` in the very same pass.

`fold()` now lowercases first. The existing check then catches `Öll`/`Øll` on its own.

### FR-R288-5 — a lexicon, so *wrongness* becomes visible and not just inconsistency

`i18n/lexicon/<code>.txt` holds every word form that language legitimately uses, one per line,
generated from the corrected files and **reviewed once by a speaker**. `check-i18n-spelling.sh`
then fails when a string contains a word that is **not** in the lexicon but whose accent-folded
form **is** — `Mal` against `mál`, `Latid` against `latið`, `Sog` against `Søg`.

This is the ground truth R287's rejected heuristic lacked. `Heim` and `samband` are in the lexicon
and pass; they never needed an accent. A genuinely new word fails loudly and is added in the same
commit that introduces it, which is the point: adding a word to the lexicon is a deliberate act a
reviewer can see in a diff.

The digraph rule is folded into the same check: `ae`, `oe` and `aa` inside a word fail unless the
word is in the lexicon. `Servaraadressa`/`Ambætaraadressa` is in the lexicon with its reason — its
`aa` is a word join, and Faroese has no `å` for an `aa` rule to produce (R287 FR-R287-2's own
finding, now written down where the check can read it).

### FR-R288-6 — a TV is a `sjónvarp`

Owner, on reviewing the pass: *"In Faroese it's usually just Sjónvarp. TV is only used when an
abbreviation is needed because of small space."*

Nineteen of the twenty-one strings that draw a TV already said `sjónvarp`. Two did not, and the
render sites decided both:

- **`livetv.guide`** — `TV-skrá` → **`Sjónvarpsskrá`**. It draws as a 24 sp full-width screen
  heading (`LiveTvGuideScreen`) and an 18 sp heading in a 420 dp panel (`LiveTvPlayerScreen`).
  Neither is short of room, and its own sibling `livetv.open_guide` already fits
  *"Lat upp sjónvarpsskrá"* into a **tighter** 16 sp slot — so the file itself proves the long form
  fits, which is R287's principle applied to width instead of spelling.
- **`livetv.guide_kicker`** — `Beinleiðis sjónvarp` → **`Beinleiðis`**. This is the one genuinely
  narrow spot (11 sp, bold, 1.5 sp letter-spacing) and the one the owner named as where `TV` could
  be earned. The owner chose to **drop the noun rather than abbreviate it**: the line directly
  beneath already says `sjónvarpsskrá`, so nothing is lost.
- **`livetv.live_badge`** — `LIVE` → **`BEINLEIÐIS`**. One of R279's eighteen byte-identical-to-
  English strings, and not raised by the owner until asked; keeping an English badge was offered
  and declined. Now the same word as the kicker and as `livetv.on_now` / `livetv.watch_live`.

**Faroese now contains no bare `TV` at all.** FR-R288-7 keeps it that way.

### FR-R288-7 — the rule is checked, not remembered

`DISCOURAGED` in `check_i18n_spelling.py`: `fo` has a real word for a TV, so a bare `tv` in
`fo.json` fails. It is deliberately **tiny, per-language, and exception-by-key** — this is the one
rule in the script about word *choice* rather than spelling, and it should not grow into a style
guide by accident. Danish is absent on purpose: `dette tv` is ordinary Danish and must keep passing.

`DISCOURAGED_OK` is **empty**, because the only line narrow enough to have earned the abbreviation
was answered by dropping the noun instead. A future entry therefore has to argue for itself in a
diff, which is the point.

**A second rule joined it: `telefonurin`.** Owner: *"the `urin` part on `telefon` is always
incorrect, we should never use that."* `telefon` is feminine — `telefonin` / `telefonina` /
`telefonini`. The file had `telefonurin` twice against `telefonini` four times, and **each string
read perfectly well on its own**; only the whole file showed the disagreement. Both masculine uses
happened to be removed by the AirPlay rewrite (FR-R288-15), so the rule guards an already-clean
file rather than fixing one — which is exactly when a guard is worth adding.

That both standing rules turned out to be **gender or word choice, not spelling**, is the argument
for keeping `DISCOURAGED` separate from the lexicon: the lexicon learns from the file, and a file
that has been wrong twice in the same way teaches the wrong thing. These two rules come from the
owner instead, and each names what to use in its place.

The failure summary distinguishes the two kinds of finding, because telling someone to "use the
real letters" when they have written a real word in the wrong language is a confusing thing for a
check to say.

### FR-R288-9 — a second owner pass, driven by questions rather than a read-through

The owner declined to read 421 strings and asked to be questioned instead, so candidates were
found mechanically — *one English concept rendered two ways* — and only those were put to them.
That heuristic is what surfaced every item below.

| was | now | why |
|---|---|---|
| `Leikfólk` / `Luttakarar` | **`Leikarar og framleiðsla`** / **`Leikarar`** | one concept, two headings; *Luttakarar* is *participants* |
| `Varðgeym` / `Vista` / `goymt` | **`Goym`** | three words for *save*; `home.showing_saved` already said `goymt` |
| `Sleppa intro` | **`Skip intro`** | owner: *"not possible to translate properly — `Skip intro` is valid Faroese"* |
| `Sleppa eftirtekstum` | **`Skip eftirtekstirnar`** | so the pair matches, and definite: it skips *the* credits |
| `Bið` (a bare imperative among four nouns) | **`Umbøn`** | the Discover tab; shares its word with `discover.retry_request` |

**Kept after review, and recorded so they are not re-raised**: `Góðska` for the quality facet
(`cast.converted_body` already uses `góðskan` for picture quality), `Uppdaga` for Discover,
`Studio` for the studios tab, `Steðga casting` (the platform's own word, and what a household says
aloud), and the `Ljóðlýsing` / `Sjónlýsing` badge pair — one syllable apart and meaning opposite
things, but each literally correct and symmetric.

**`action.save` is drawn by no client.** It was `Vista`, a third word for *save* that no viewer
could ever have seen. It is now `Goym` like the other two rather than deleted, because the key set
is a non-goal of this phase — but it is dead weight worth a look in a later pass.

### FR-R288-10 — four neighbouring concepts, four words

Renaming *Networks* pulled a thread. Three different things were sharing two words, crossed:
a **broadcaster** was `Støðir` in three strings but `Rás` in `up.network`; a **live-TV channel**
was `rás` twice, `sjónvarpsstøð` twice and `støð` once; and a **Ravilo channel** — a collection the
household configures — was also `Rás`. So `rás` meant three things and `støð` meant two.

Now: a broadcaster is a **`sjónvarpsrás`**, a live-TV channel is a **`rás`**, a film studio stays
**`Studio`**, and a Ravilo collection is **`Samling`** / **`Savn`**. `støð` is gone from the file
entirely, and `seg.studios`' translator note — which still told the next translator *"Støðir for
networks"* — now names all four concepts and says not to merge them.

⚠ **`browse.facet.channel` is `Savn`, which again overlaps `nav.library`.** That is the collision
FR-R288-1 moved *Collections* off `Savn` to avoid. The owner chose it knowingly: it agrees with
`browse.empty_channel` (*"Einki í hesum savninum enn"*), and a facet inside Browse is a quieter
place than a bottom-bar label. Recorded rather than silently re-fixed.

### FR-R288-11 — the Upcoming rail could not tell the future from the past

The Upcoming screen exists to show what has **not happened yet**, and six of its labels used the
past participle: *Airs today* was `Sent í dag` — *broadcast today* — sitting on the same rail as
`Sent í gjár` for *Aired yesterday*. **Identical construction for both tenses**, distinguished only
by the day word. The same for films: `Latið út í dag` for *Releases today*.

The file already knew the future form — `sonarr.airs` says `verður sendur` — which is why this was
a slip rather than a gap.

The owner split it by kind, because an episode is broadcast and a film comes out:

| | future | past |
|---|---|---|
| **airs** (an episode) | `Kemur út í dag` · `…í morgin` · `…um {n} dagar` | `Kom út í gjár` · `Kom út fyri {n} døgum síðan` |
| **releases** (a film) | `Verður útgivið í dag` · `…í morgin` · `…um {n} dagar` | `Varð útgivið í gjár` · `Varð útgivið fyri {n} døgum síðan` |

Three further strings carried the retired verbs for the same concepts and followed without a
separate decision: `up.missing_sub` and `up.foot_missing` (*Released…* → `Útgivið…`) and
`up.foot_upcoming` (*when it airs or releases* → `tá tað kemur út ella verður útgivið`).

**`player.badge_signs_only` was `Bara skilti`** — *skilti* is signage, a signpost, not
subtitles-for-signs, and `bara` was the file's only one where every other string says `bert`. It is
now **`Bert fremmant mál`**, chosen by the owner after three rounds of candidates.

**Kept after review**: `Reinsa` for Clear and `Angra` for Cancel.

### FR-R288-12 — the last sweep: hapaxes, English survivors, and one more agreement

With the "one concept, two words" seam exhausted, the remaining candidates were found three other
ways: words appearing **exactly once** (nothing in the file corroborates them), strings still
**byte-identical to English**, and **long sentences** (most room to hide a slip).

- **`error.load.reauth.body`** said `Setan hjá hesi eindini er runnin út.` — *setan* for a session
  appeared nowhere else in the file. Rewritten with no session noun at all:
  **`Henda eindin er ikki innritað longur.`**, which reuses the verb the whole app already signs in
  with.
- **`request.empty`** had the English word `feeds` sitting inside a Faroese sentence →
  **`keldur`**.
- **`fd.nodesc`** was `Onga lýsing enn` — accusative where a standalone statement wants the
  nominative, and its own siblings (`browse.empty`, `search.empty`, `tx.empty`) already used it.
  Now **`Eingin lýsing enn`**.
- **`web.fullscreen_title`** was `Skift fullan skerm` — *change full screen*, with no sense of
  switching it on or off, for English's *Toggle fullscreen*. Now
  **`Slá fullan skerm til/frá`**.

**Kept after review**: `Admin` and `Trailer` (the two remaining English survivors a viewer sees,
both what a household says aloud), `Sendidagur` for the Air date row, and — deliberately —
`error.play.unreachable.title`'s **`Fekk ikki samband`**, which names the connection rather than
the server while `login.error_unreachable` and `cast.no_server` name the `ambætari`. Three
renderings of one English sentence, kept on purpose: R237's player errors avoid naming a component
the viewer cannot see.

The other fifteen English-identical strings are correct as they are: the month abbreviations,
`Stereo`, `Surround 5.1`, `Sonarr`, `{n} min` and the ordinal suffix.

### FR-R288-13 — agreement, the class no rule reaches

Once spelling was machine-enforced, every remaining defect had the same shape: **every word spelled
correctly, the wrong word or the wrong form chosen.** These were found by putting candidates to the
owner, never by a check, and the file's own evidence is what identified most of them.

- **`row.new_movies` was `Nýggjar filmar`** — the *feminine* plural adjective on a masculine plural
  noun. Its sibling `row.new_series` (`Nýggjar seriur`) is right, because `seria` is feminine and
  `filmur` is not, and the file says so four times (`Filmur`, `Endi á filminum`). Now
  **`Nýggjir filmar`**, and `row.new_all` became **`Nýtt í savninum`**.
- **`detail.episodes_watched`** was `pørtum sædd` → **`pørtum sæddir`**, masculine plural agreeing
  with `partar`. Drawn under every series hero, and wrong from the moment `partur` was chosen —
  by this phase.
- **`fd.nodesc`** accusative → nominative (above).
- **`browse.maturity.any`** was `Øll` for English **Any**, the value of the maturity range rows.
  Now **`Einki mark`** — *no limit*, which is what an unset bound means. Note the owner corrected
  the gender of my own suggestion (`Eingin mark` → `Einki mark`; `mark` is neuter).
- **`cast.lost_sub`** said `Hon spælir kanska enn` — a feminine pronoun for a TV. Now
  **`Sjónvarpið spælir kanska enn. Ravilo kann ikki ná tí.`** `srv.busy_sub`'s `Hon` referred to the
  playback rather than a device, and lost its pronoun entirely: **`Byrjar, so skjótt sum gjørligt.`**

**Cast stays English, by owner decision.** `cast.connected` is **`Cast til {device}`** — *"we want
to keep using cast/casting instead of translating that word"* — alongside `cast.stop`'s
`Steðga casting`. The rest of the `cast.*` family keeps its Faroese verbs.

**Kept after review**: `Tilpassa`/`Fyll`, `Strika filtur`, `Sendidagur`, `screens.code_hint`,
`settings.unpair_desc`.

### FR-R288-14 — an automated agreement scan, and why it is not the answer

After `Nýggjar filmar`, a gender-agreement scan was written: a table of noun genders taken from the
file's own unambiguous uses, matched against every preceding determiner and adjective. Across 421
strings it produced **exactly one hit, and that hit was wrong.**

`settings.unpair_desc`'s *"Tekur **allar vangar** burtur"* looked like the same defect — a feminine
plural on the masculine `vangi`. The owner: *"`allar` er feminine plural tá vit eru í hvørfall, men
`allar` kann vera bæði kallkyn og kvennkyn tá tað er hvønnfall fleirtal."* The noun is the object,
so it is accusative, where masculine and feminine share the form. The scan had no notion of case.

This is R287's rejected stem heuristic all over again, and it is recorded for the same reason: **a
rule with no grasp of the thing it is judging produces confident noise.** `Nýggjar filmar` was
caught by a person reading a row title, not by the scan. The scan is not kept.

**`app` is feminine.** The file had it three ways — `appinum` (masculine/neuter dative),
`appini` (feminine dative), `appina` (feminine accusative) — and nobody had noticed, because each
string reads fine alone. Now `appin` / `appina` / `appini` throughout, which keeps two of the three
and rewrites `install.signin_again`.

Also: `player.stinger_badge` `Sena eftir eftirtekstir` → **`Eftir eftirtekstirnar`**, definite like
`Skip eftirtekstirnar`; `screens.busy` lost a dangling preposition (`hyggur at` → `hyggur`).

**Kept after review**: `Deil` for the iOS Share step — even though iOS has no Faroese and the
button the viewer is looking at will say *Share* or *Del* — and the `Vísir nú` / `Brúkt seinast`
picker pair.

### FR-R288-15 — where the seams ran out

Eleven rounds of questions were driven by mechanical candidate-finding, never by reading the file
end to end (the owner: *"I don't want to read through it. Keep asking me questions"*). The seams,
in the order they were worked and exhausted:

1. **one English concept rendered two ways** — found *Leikfólk/Luttakarar*, *Varðgeym/Vista/goymt*,
   *Bið*, and the whole *støð/rás* tangle;
2. **words appearing exactly once** — found `Setan`;
3. **strings byte-identical to English** — found `feeds`, and confirmed fifteen that are correctly
   identical;
4. **long sentences** — found the garbled and the over-compounded;
5. **grammatical person across progressives** — found `Royni`;
6. **tense against the screen's own purpose** — found the whole Upcoming rail;
7. **gender agreement** — found `Nýggjar filmar`, and produced one false positive (FR-R288-14);
8. **strings this phase itself authored** — found `pørtum sædd`, `Meiri kunning`, and a word I had
   invented;
9. **Faroese vs Danish length ratio** — six hits, **all benign**: Faroese is simply more
   compound-heavy. The seam is recorded as unproductive so it is not tried again.

What is left after that is two deliberate overlaps (`Savn` for Library and the channel facet;
`Næsti partur` for both *Next Up* and *Next episode*), each confirmed by the owner, and nothing
else a mechanism can see.

**One latent defect fixed as a by-product.** The owner's rewrite of the two AirPlay strings removed
the only two masculine uses of `telefon` (`telefonurin`); the other four already said `telefonini`.
So *telefon* is now consistently feminine without anyone having to raise it — the same defect as
`app`, caught by luck rather than by method, which is the honest note to end on.

### FR-R288-16 — the next-episode button agrees with `partur`

`player.next` (the TV player's `">> …"` pill, `PlFocus.NEXT_EP`), `pl.next` (the phone rail) and
`player.up_next` (the kicker on the episode card, the multi-episode card, the credits card and the
cast remote) all read `Næsta` / `NÆSTA` — the feminine/oblique form of *næstur*.

FR-R288-1 made an episode a **`partur`**, which is masculine, so the agreeing form is **`Næsti`** —
and `row.next_up` and `sonarr.next_ep` already said `Næsti partur`. The three player strings were
disagreeing with the two other strings that name the same thing. Now `Næsti` / `NÆSTI`.

Room was never the constraint: the TV pill sits beside `Ljóð & undirtekstir`, and the phone rail
already carries `Undirtekstir`. The owner chose the bare `Næsti` over `Næsti partur` at both, so
the noun stays implied exactly as English's bare *Next* implies it.

**The other three forms of *næstur* in the file are each correct and stay** — this is agreement,
not a spelling to unify:

| form | where | why |
|---|---|---|
| `Næsti` | `player.next` · `pl.next` · `row.next_up` · `sonarr.next_ep` | nominative masculine, agreeing with `partur` |
| `næsta` | `settings.autoplay_next` — *Spæl næsta part* | accusative masculine |
| `næstu` | `account.pw_sub` — *næstu ferð* | `ferð` is feminine |
| `Næst` | `livetv.next` | adverbial, the live Now/Next pair with `Nú` |

### FR-R288-17 — the plural of `seria` is `seriur`

Owner: *"`seriar` is bad Faroese. Let's change it to `seriur`."* Four strings carried the wrong
plural — `nav.series`, `up.series`, `row.new_series` and `tx.sub_networks`.

**The file had already contradicted itself and nobody noticed.** `request.search_hint` says
`serium` (dative plural) and `discover.go_to_series` says `seriuna` (definite accusative singular).
Both are only correct on a `seriu-` stem — that is, on a `seriur` plural. So four strings were
inflecting one way and two another, in one paradigm, and every word was spelled correctly.
The paradigm now reads `Seria` · `Seriur` · `serium` · `seriuna` throughout.

### FR-R288-18 — the media-type filter says `Alt`, not `Øll`

`lib.type.all` is the "All" option of the Library type pill (`libraryTypeLabel`,
`BrowseKind.ALL`), drawn beside *Filmar · Seriar · Tónleikur · Mín listi*. It said `Øll`; the owner
says `Alt`. Its two sibling "All" filter chips — `browse.all` and `up.all` — already said `Alt`, so
it was the odd one out of three.

`Øll` survives in exactly one place, correctly: **`screens.all` = `Øll tíni sjónvørp`**, where it
agrees with the neuter plural noun it qualifies. The rule is not "never `øll`" — it is that a
**standalone** filter value is `Alt`.

⚠ **Left alone, and named so it is not mistaken for an oversight:** `browse.maturity.any` is also
a standalone `Øll`, but its English is **"Any"**, not "All", and it draws as the *value* of the
maturity range rows — *Frá: Øll · Upp til: Øll*. Different word, different control, not covered by
the owner's answer. Raised, not guessed.

### FR-R288-19 — `loading` is `Heintar…`

Owner: *"Loading might be different in different contexts in Faroese. But let's go with
`heintar`."* `loading` was `Løðir…` — inherited from R264's Tizen receiver table, which is why
R279 took it as canonical when the two tables disagreed (the other copy was accent-stripped).
`Løðir` now appears nowhere.

The owner's caveat is the interesting part and is recorded rather than resolved: **one key serves
eight render sites** — Search, Browse, Settings, Discover, Channel, Seerr search and twice in
`PlayerScreen` — so if Faroese really wants different words for *fetching a list* and *starting a
stream*, that is a **key split**, not a translation, and it would have to be argued in English
first. Nothing here assumes it.

`acq.fetching` is also `Heintar`, for English's *Fetching*. That collision is harmless: it renders
only in `RequestScreen`, which never draws `loading`.

### FR-R288-20 — one grammatical person for a progressive

Faroese inflects a present-tense verb for person; English and Danish do not, so this is a choice
only the Faroese file has to make, and it made it two ways.

**Fifteen of seventeen** progressive strings are third person — `Løðir…`, `Arbeiðir…`,
`Varðgeymir…`, `Ritar inn…`, `Heintar`, `Leggur inn…`, `Vísir nú`, `Skiftir…`, `Sambindur…`,
`Sendir…`, `bíðar`, `byrjar`. Two were first person, and both are fixed:

- **`loading.still_trying`** — `Royni framvegis…` → **`Roynir framvegis…`**. This is the one that
  matters, because `PlayerScreen` stacks it **10 dp below `loading` in the same `Column`**
  (R237 FR-R237-5 adds it to R218's cold start), so a viewer reads *"Løðir… / Royni framvegis…"* —
  third person then first — in a single glance. `receiver.setup_trying` already carries the same
  verb in the third person: `Roynir at sambinda…`.
- **`home.showing_saved`** — `…royni aftur at knýta samband` → **`…roynir…`**. This one
  contradicted *itself*: `Vísir` (third) and `royni` (first) in one sentence.

⚠ **Recorded because it is the mistake this phase exists to prevent**: `home.showing_saved` read
`royna` before this phase, and R288's first pass changed it to `royni` **to match
`loading.still_trying`** — aligning to the outlier instead of to the file's own fifteen. It was
caught only because the owner asked what `loading` says in Faroese. The lexicon cannot see this
class at all: every one of these words is spelled correctly. Grammatical agreement is not spelling,
and nothing checks it.

### What the check cannot see, stated plainly

The last four findings — `Øll` for a filter's *All*, `Royni` for a third-person progressive,
`seriar` for a feminine plural and `Næsta` for a masculine nominative — share a shape, and it is the shape of everything left:

> **Every word was spelled correctly. The wrong word, or the wrong form of the right word, was
> chosen.**

The lexicon and the digraph rule close the *spelling* class for good. They are structurally blind
to agreement, inflection and word choice, and no amount of extending them would help, because the
ground truth for those is a sentence, not a word. All three were found by the owner reading the
strings — which is the honest answer to "how do we keep Faroese correct": these need a speaker, and
the checks exist so a speaker's attention is never spent on `Naest` again.

`browse.maturity.any` was closed in FR-R288-13, for the same reason: it needs a
judgement, not a rule.

### FR-R288-8 — Danish, the same defect

`Sog`/`soge` → `Søg`/`søge` (4 keys) and `Born` → `Børn`. Found by the same lexicon rule and fixed
in the same pass because it is the same defect, not because Danish was in scope. `laveste` is
correct Danish and is in the lexicon.

## Acceptance

1. `cast.stop` reads `Steðga casting`.
1b. No Faroese string contains a bare `TV`, and `check-i18n-spelling.sh` fails if one is put back.
2. Settings in Faroese reads *Útsjónd · Spæling · Mál · Vís framgongd á Halt fram at síggja ·
   Spæl næsta part sjálvvirkandi* — R287's acceptance test #1, now actually met.
3. No Faroese string contains `bogvahandan`, `Ofrlinu`, `Comandi`, `Stovna casting` or `Öll`.
4. One word per row of FR-R288-1's table appears in the file, and the others do not.
5. R287's three exclusions still hold: `browse.sort.az` is `A–Á`, `up.episodes_range` is
   `Partar {a}–{b}` and substitutes, `profile.signed_in` keeps its `/ur` ending.
6. The key set of every `i18n/*.json` is unchanged and `generateRaviloStrings` still succeeds.
7. `scripts/check-i18n-spelling.sh` passes, and fails when any one string is reverted to `Mal`,
   `Naest` or `pa`.
