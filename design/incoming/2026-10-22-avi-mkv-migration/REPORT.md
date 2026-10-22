# The container-migration round — what was promoted

`avi-mkv-migration`, received 2026-10-22 (`596ef867`), promoted 2026-10-22 for DEB-2137.
The spec this serves is
[`specs/requirements/an-avi-language-edit-offers-a-conversion-to-mkv.md`](../../../specs/requirements/an-avi-language-edit-offers-a-conversion-to-mkv.md).

The round's own directory is pruned in the promotion commit, as `design/PROCESS.md` says. Its
content lives in `design/app/` now, which is the archive; its own commit holds the rest. This file
is what a reader needs after the prune, and nothing else.

## What was promoted, and where

| From the round | To | Why there |
|---|---|---|
| `app/container-convert.css` (`.cv-*`) | **`design/app/detail.css`**, appended by hand | detail.css is the media detail page's own stylesheet, already served by `syncDesignAssets`, already linked by `media.html` and by the real wasmJs app, and the shared `.modal` / `.modal-back` dialog shell the confirmation reuses is defined in it. |
| `app/container-convert.js` (direction C harness) | **`design/app/container-convert.js`**, rewritten for direction A | Mockup-only, like `app-shell.js` and `ravilo-builders.js`. Not in `syncDesignAssets`; the real admin draws these states from the server payload in Kotlin. |
| the round's `#cv-mount` slot | `design/app/media.html`, above the Tracks card | One `<div id="cv-mount">` and one `<script>`. No new `<link>`: detail.css was already linked. |
| `avi-mkv-migration/` (directions canvas, 22 PNGs) | nothing | A pick-a-direction artefact. The direction is picked; the strings it carried are the table below. |
| `edited-in-place/media.html` | nothing | A copy of the tracked mockup with the round's slot in it. The slot is now in the tracked file; a second copy is the shape the nineteen wipes were made of. |

**Directions B and C were not built**, per the spec. The round's `.cv-std` / `.cv-card.hot`
(direction C's standing card) and its in-row drawer are deliberately absent from production CSS.

## The owner's answers, as they changed the drawing

- **D2** — the original goes into the hidden `.jellystructure/` folder and is kept until he removes
  it. Every *"stays beside the new file"* / *"original · kept beside it"* is rewritten; the verified
  state now shows the **hidden path** on disk, not the old one. **Every dashed `Tentative` badge is
  gone**, and `.badge.cv-tent` was never promoted — there is nothing tentative left to mark.
- **D3** — the destination file name is shown as text, not an `<input>`. `fnameHint` is dropped, and
  the dialog's label went from *Proposed destination* to **Destination**: "proposed" described the
  editable field, and keeping it beside fixed text would have contradicted the decision.
  `.cv-fname` replaces `.cv-fld input`.
- **FR-5.4 / FR-5.5** reuse the Blocked notice with their own sentences. No new frame and no new
  class, as the spec says.
- **FR-8** — the verified state gained the Radarr line as a second `.cv-pend` block beside *Waiting
  for Jellyfin to index*, the same kind of secondary line.

## What only the pictures showed

Five defects, none of which any fence can see:

0. **The episode variant still offered the conversion.** FR-2 drops the row's link for an episode
   because the conversion is films-only — but the notice above it was still drawn, *with* **Review
   the conversion to MKV** in it. Dropping the link while leaving the offer one line above is worse
   than either. An episode now gets the sentence and nothing else.

1. **The frames rendered in the light theme, and the round had only ever been drawn dark.** The
   admin's theme is a three-way Light/Dark/System picker (`app-shell.js`), System is the default,
   and headless Chromium reports light. Light is a production surface and the promotion had to hold
   in it.
2. **Coloured text on the light theme was unreadable.** `--ok` / `--warn` / `--bad` are *not*
   re-tinted per theme in `wf.css` — it overrides the text colour at each use site instead (its
   `.badge.ok` / `.warn` / `.bad` light rules). The round's `.cv-keep`, `.cv-unk`, `.cv-offb b`,
   `.cv-ico.pth` and `.cv-steps li.x i` used the tokens raw, which is a pale mint and a pale amber
   on white. Fixed with `[data-theme="light"]` overrides carrying `wf.css`'s own values.
3. **The focus ring was hard-coded `#c6bcfb` — which is the *dark* value of `--acc-ink`.** On the
   light theme's white card it is nearly invisible. Now `var(--acc-ink)`, which is correct in both.

Plus the one the round's own MANIFEST flagged: `container-convert.css` ended with a **bare
`.btn:focus-visible`**. `index.html` links every page's CSS unconditionally and `detail.css` loads
after `wf.css`, so that single rule would have put a focus ring on every button in the admin. It is
now written only under `.cv-card` and `.cv-dlg`.

Two smaller ones the magnified frames caught: the heading's focus ring spanned the whole card,
which reads as a text field beside a file name that D3 has just made *not* editable (now
`width: fit-content`); and in *verified* the kept original's badge dropped to a line of its own
while the new file's stayed inline, because the hidden folder makes that path the longer of the
two — a badge that has left its own row no longer labels anything (`flex: 1` / `flex: none`).

## Pre-existing defects in the mockup, found while rendering and deliberately NOT fixed here

These are the shipped mockup's own, they predate this round, and each is a **shared layout class**.
Changing one in a promotion commit is the unreviewed-wide-change shape `PROCESS.md` warns about, so
they are reported rather than patched. For Bárður to decide whether any is a card:

1. **The tracks panel overflows its own viewport at 390 px, by 133 px.** `.col.fill` is a flex item
   with the default `min-width: auto`, so it overflows its `.row` whatever is inside it:
   `detail-default`, with an **empty** `#cv-mount`, overflows by exactly the same amount
   (`scrollWidth` 523 against a 390 viewport; the untouched mockup is worse, at 669). The fix is the
   one-property one this repo has already made twice for `.shell` and fences in
   `check-mobile-css.sh`: `.col.fill { min-width: 0 }`. **The 390 px frames here were rendered with
   that one declaration injected at render time** — otherwise they would show a clipped page rather
   than direction A, and Acceptance 5 could not be read off them.
2. **The Tracks card header's `Audio | Subtitles` segmented control collapses to a 4 px sliver at
   390 px**, visible in every narrow frame as a thin vertical bar beside the card title. Same family
   — a flex child with nothing stopping it shrinking.
3. The da/fo frames show the conversion's strings translated and the **card's own chrome still in
   English** (*Tracks — language & order*, the reorder footnote). That is correct: only the
   `migrate.*` keys are this work's. Worth knowing before reading the drafts.
4. The `limitation-episode` frame borrows the film page's chrome, so its breadcrumb still reads
   *Movies / Sample Film*. Only the row sentence in it is the episode behaviour being shown.

## One promoted rule that reaches past the notice

`@media (max-width: 640px)` now restacks **the track row itself** — `.trk-cols` hidden and
`.trk-main` down to three columns, dropping # and kind. This is direction A's own drawn narrow
treatment, and the spec adopts the drawn lean except where it lists a change, so it is promoted.
It is called out here because it is the one rule in this commit that changes a shipped component
beyond the conversion flow: without it the five desktop columns survive at phone width and squeeze
the language, codec and Triage badge into a stack one word wide, which is the failure round 9 found
at 412 px.

## Not done here — it needs a seat that may write `scripts/`

The spec's *Design* section asks for a `SCOPED_FILES` row in `check-css-scoping.sh`. **That row is
not needed and was not added**, and the reason is the file choice above: `SCOPED_FILES` fences a
*new page-scoped stylesheet*, and this promotion created none. `detail.css` is an existing served
file that the fence deliberately does not list, and every `.cv-*` class was checked by name against
`wf.css`, `app.css` and the other eleven served sheets before promotion — none collides.

What **is** still owed, and what this seat may not write, is the `check-mobile-css.sh` register:
`design/PROCESS.md`'s promotion gate and invariant 6 both require a line per hand-authored rule the
promotion introduces. The exact lines are in the task comment for whoever owns `scripts/`.

## The strings (FR-10)

97 keys, `migrate.*`, complete in all three languages, generated from the `STR` object in
`design/app/container-convert.js` so the table cannot drift from what renders. **English ships as
written. Danish and Faroese are drafts and need the owner's read on the QA row** (spec Acceptance 6).
A key that already ships in `i18n/*.json` — the language names, *Cancel*, the seeding sentence,
*Activity ▸ Jobs* — keeps its shipped string; the copy here is only so the mockup renders.

| key | English | Danish (draft) | Faroese (draft) |
|---|---|---|---|
| `migrate.unknown` | Unknown | Ukendt | Ókent |
| `migrate.fao` | Faroese (fao) | Færøsk (fao) | Føroyskt (fao) |
| `migrate.triage` | No language tag · in Triage | Intet sprogmærke · i Triage | Einki málmerki · í Triage |
| `migrate.notKept` | Not kept — this AVI can’t store a language. | Ikke gemt — denne AVI kan ikke gemme et sprog. | Ikki goymt — henda AVI-fílan kann ikki goyma eitt mál. |
| `migrate.requested` | Requested: {lang}. | Ønsket: {lang}. | Ynskt: {lang}. |
| `migrate.why` | Why, and what you can do | Hvorfor, og hvad du kan gøre | Hví, og hvat tú kanst gera |
| `migrate.stillFail` | Still Unknown — the conversion to MKV didn’t finish. | Stadig Ukendt — konverteringen til MKV blev ikke færdig. | Enn Ókent — umbroytingin til MKV varð ikki liðug. |
| `migrate.stillUnv` | Still Unknown — the new file had no language tag. | Stadig Ukendt — den nye fil havde intet sprogmærke. | Enn Ókent — nýggja fílan hevði einki málmerki. |
| `migrate.blkRow` | Not kept — converting this file to MKV is blocked right now. | Ikke gemt — konvertering af filen til MKV er blokeret lige nu. | Ikki goymt — at umbroyta fíluna til MKV er forðað júst nú. |
| `migrate.pthRow` | Not kept — migration is unavailable for this file. | Ikke gemt — migrering er ikke mulig for denne fil. | Ikki goymt — flyting er ikki møgulig fyri hesa fílu. |
| `migrate.seeAbove` | See above | Se ovenfor | Sí omanfyri |
| `migrate.locked` | Locked while the conversion runs | Låst, mens konverteringen kører | Læst, meðan umbroytingin gongur |
| `migrate.fileScope` | File · {file} | Fil · {file} | Fíla · {file} |
| `migrate.limBadge` | AVI limitation | AVI-begrænsning | AVI-avmarking |
| `migrate.limTitle` | This AVI can’t keep an audio language | Denne AVI kan ikke beholde et lydsprog | Henda AVI-fílan kann ikki varðveita eitt ljóðmál |
| `migrate.limBody` | AVI has no place to store a language for {track}, so {lang} can’t be saved in this file. Converting the file to MKV can keep it. | AVI har ingen plads til et sprog for {track}, så {lang} kan ikke gemmes i denne fil. Konverteres filen til MKV, kan sproget gemmes. | AVI hevur einki pláss at goyma mál fyri {track}, so {lang} kann ikki goymast í hesi fíluni. Verður fílan umbroytt til MKV, kann málið goymast. |
| `migrate.streamCopy` | Video and audio are copied without re-encoding | Video og lyd kopieres uden genkodning | Video og ljóð verða avritað uttan umkoding |
| `migrate.review` | Review the conversion to MKV | Gennemse konverteringen til MKV | Kanna umbroytingina til MKV |
| `migrate.keep` | Keep as AVI | Behold som AVI | Varðveit sum AVI |
| `migrate.keepNote` | Keep as AVI: the language stays Unknown and the track stays in Triage. | Behold som AVI: sproget forbliver Ukendt, og sporet bliver i Triage. | Varðveit sum AVI: málið verður verandi Ókent, og sporið verður verandi í Triage. |
| `migrate.pvTitle` | Convert to MKV — what would change | Konvertér til MKV — hvad der ændres | Umbroyt til MKV — hvat broytist |
| `migrate.container` | Container | Container | Fíluílat |
| `migrate.ext` | Extension | Filendelse | Fíluending |
| `migrate.streams` | Streams | Spor | Spor |
| `migrate.copied` | copied untouched | kopieres uændret | avritað óbroytt |
| `migrate.langReq` | Language requested | Ønsket sprog | Ynskt mál |
| `migrate.estimate` | Estimate | Skøn | Mett |
| `migrate.estVal` | about 2 minutes · 1.4 GB of temporary space | ca. 2 minutter · 1,4 GB midlertidig plads | umleið 2 minuttir · 1,4 GB av fyribilsplássi |
| `migrate.tempWhere` | Temporary files go in a hidden folder beside the source, on the same disk. | Midlertidige filer lægges i en skjult mappe ved siden af kilden, på samme disk. | Fyribilsfílur verða lagdar í eina fjalda mappu við síðuna av keldufíluni, á sama diski. |
| `migrate.estUnk` | Estimate unavailable | Intet skøn | Einki mett |
| `migrate.estUnkBody` | Time and temporary space can’t be worked out for this file. | Tid og midlertidig plads kan ikke beregnes for denne fil. | Tíð og fyribilspláss kunnu ikki roknast út fyri hesa fílu. |
| `migrate.before` | Before it starts | Før start | Áðrenn byrjað verður |
| `migrate.beforeVal` | Torrent safety is checked again when you press Start migration. | Torrent-sikkerheden tjekkes igen, når du trykker Start migrering. | Torrent-trygdin verður kannað aftur, tá ið tú trýstir á Byrja flyting. |
| `migrate.oldFile` | Old file | Gammel fil | Gamla fílan |
| `migrate.oldVal` | {file} moves into a hidden folder beside the new file and is kept until you remove it. | {file} flyttes ind i en skjult mappe ved siden af den nye fil og bliver liggende, indtil du fjerner den. | {file} verður flutt inn í eina fjalda mappu við síðuna av nýggju fíluni og verður verandi, til tú strikar hana. |
| `migrate.cont` | Continue to confirmation | Fortsæt til bekræftelse | Halt fram til váttan |
| `migrate.cancel` | Cancel | Annuller | Angra |
| `migrate.cfTitle` | Convert {title} to MKV? | Konvertér {title} til MKV? | Umbroyt {title} til MKV? |
| `migrate.source` | Source | Kilde | Kelda |
| `migrate.dest` | Destination | Destination | Nýtt stað |
| `migrate.fname` | File name | Filnavn | Fílunavn |
| `migrate.srcStays` | Stays in place until the new file is verified | Bliver liggende, indtil den nye fil er bekræftet | Verður liggjandi, til nýggja fílan er váttað |
| `migrate.start` | Start migration | Start migrering | Byrja flyting |
| `migrate.close` | Close | Luk | Lat aftur |
| `migrate.blkBadge` | Blocked | Blokeret | Forðað |
| `migrate.blkTitle` | This file can’t be converted right now | Denne fil kan ikke konverteres lige nu | Henda fílan kann ikki umbroytast júst nú |
| `migrate.seedShort` | This file is linked to an active seeding torrent | Denne fil er knyttet til en aktiv torrent, der seeder | Henda fílan er knýtt at einum virknum torrenti, sum seedar |
| `migrate.seedLong` | This file is shared with a torrent that is still seeding (hard link), so it can’t be changed in place. | Denne fil deles med en torrent, der stadig seeder (hårdt link), så den kan ikke ændres på stedet. | Henda fílan er deild við ein torrent, sum enn seedar (hart leinki), so hon kann ikki broytast á staðnum. |
| `migrate.unkShort` | The torrent safety check couldn’t be reached | Torrent-sikkerhedstjekket kunne ikke nås | Torrent-trygdarkanningin kundi ikki náast |
| `migrate.unkBody` | So this file is treated as shared. Migration stays unavailable until the check answers. | Derfor behandles filen som delt. Migrering er ikke mulig, før tjekket svarer. | Tí verður fílan viðgjørd sum deild. Flyting er ikki møgulig, fyrr enn kanningin svarar. |
| `migrate.takenShort` | A file named {file} is already there, so nothing was converted. | Der ligger allerede en fil med navnet {file}, så intet blev konverteret. | Ein fíla við navninum {file} er longu har, so einki varð umbroytt. |
| `migrate.takenBody` | Nothing on disk was touched. Move or remove that file, then check again. | Intet på disken blev rørt. Flyt eller fjern den fil, og tjek så igen. | Einki á diskinum varð nert. Flyt ella strika ta fíluna, og kanna so aftur. |
| `migrate.roomShort` | Not enough free space on this disk: {need} needed, {free} free. | Ikke nok ledig plads på denne disk: {need} krævet, {free} ledigt. | Ikki nóg mikið leyst pláss á hesum diski: {need} kravt, {free} leyst. |
| `migrate.roomBody` | The conversion needs room for a second copy of the file while it runs. Free some space, then check again. | Konverteringen har brug for plads til en kopi mere af filen, mens den kører. Frigør noget plads, og tjek så igen. | Umbroytingin hevur tørv á plássi til eitt avrit afturat av fíluni, meðan hon gongur. Ger pláss leyst, og kanna so aftur. |
| `migrate.checkAgain` | Check again | Tjek igen | Kanna aftur |
| `migrate.openSeeding` | Open the Seeding tab | Åbn fanen Seeding | Opna Seeding-teigin |
| `migrate.whyNeeded` | Why it came up: AVI can’t store a language for {track}, so {lang} needs a conversion to MKV. | Hvorfor det dukkede op: AVI kan ikke gemme et sprog for {track}, så {lang} kræver en konvertering til MKV. | Hví hetta kom fram: AVI kann ikki goyma mál fyri {track}, so {lang} krevur, at fílan verður umbroytt til MKV. |
| `migrate.pathBadge` | Path mismatch | Sti stemmer ikke | Leiðin samsvarar ikki |
| `migrate.pathTitle` | Migration unavailable | Migrering ikke mulig | Flyting ikki møgulig |
| `migrate.pathBody` | The recorded path does not match the source file; migration is unavailable until the path is resolved | Den registrerede sti svarer ikke til kildefilen; migrering er ikke mulig, før stien er afklaret | Skrásetta leiðin samsvarar ikki við keldufíluna; flyting er ikki møgulig, fyrr enn leiðin er greidd |
| `migrate.runBadge` | Running | Kører | Koyrir |
| `migrate.runTitle` | Converting to MKV | Konverterer til MKV | Umbroytir til MKV |
| `migrate.step1` | Copy streams into MKV | Kopiér spor over i MKV | Avrita spor yvir í MKV |
| `migrate.step2` | Re-check the language tag | Kontrollér sprogmærket igen | Kanna málmerkið aftur |
| `migrate.step3` | Put the new file in place | Sæt den nye fil på plads | Set nýggju fíluna á pláss |
| `migrate.stepOf` | Step {n} of 3 | Trin {n} af 3 | Stig {n} av 3 |
| `migrate.orig` | The original stays untouched until the re-check passes. | Originalen forbliver urørt, indtil kontrollen er bestået. | Upprunafílan verður óbroytt, til kanningin er góðkend. |
| `migrate.leave` | You can leave this page. The job keeps running on the server — follow it in Activity ▸ Jobs. | Du kan forlade siden. Jobbet kører videre på serveren — følg det under Aktivitet ▸ Jobs. | Tú kanst fara av síðuni. Arbeiðið heldur fram á ambætaranum — fylg við í Virksemi ▸ Arbeiði. |
| `migrate.okBadge` | Verified | Bekræftet | Váttað |
| `migrate.okTitle` | Converted to MKV — language confirmed | Konverteret til MKV — sprog bekræftet | Umbroytt til MKV — mál váttað |
| `migrate.okBody` | The re-check found {lang} on {track} in {file}. | Kontrollen fandt {lang} på {track} i {file}. | Kanningin fann {lang} á {track} í {file}. |
| `migrate.nowDisk` | Now on disk | Nu på disken | Nú á diskinum |
| `migrate.newIn` | new · in place | ny · på plads | nýggj · á plássi |
| `migrate.origKept` | original · kept in a hidden folder | original · gemt i en skjult mappe | upprunalig · goymd í eini fjaldari mappu |
| `migrate.keptUntil` | Kept until you remove it. | Bliver liggende, indtil du fjerner den. | Verður verandi, til tú strikar hana. |
| `migrate.othersH` | Before other devices see it | Før andre enheder ser den | Áðrenn onnur tól síggja hana |
| `migrate.others` | Other devices still see the old file until Jellyfin has indexed the new one. | Andre enheder ser stadig den gamle fil, indtil Jellyfin har indekseret den nye. | Onnur tól síggja enn gomlu fíluna, til Jellyfin hevur skrásett ta nýggju. |
| `migrate.waitIdx` | Waiting for Jellyfin to index | Venter på, at Jellyfin indekserer | Bíðar eftir, at Jellyfin skrásetur |
| `migrate.radarr` | Radarr hasn’t taken the new file — re-link it in Radarr. | Radarr har ikke taget den nye fil — kæd den sammen igen i Radarr. | Radarr hevur ikki tikið nýggju fíluna — knýt hana saman av nýggjum í Radarr. |
| `migrate.radarrBadge` | Needs a re-link in Radarr | Skal kædes sammen igen i Radarr | Skal knýtast saman av nýggjum í Radarr |
| `migrate.failBadge` | Failed | Mislykkedes | Miseydnaðist |
| `migrate.failTitle` | The conversion to MKV didn’t finish | Konverteringen til MKV blev ikke færdig | Umbroytingin til MKV varð ikki liðug |
| `migrate.failBody` | Nothing was put in place. The original {file} is untouched and still there. | Intet blev sat på plads. Originalen {file} er urørt og ligger der stadig. | Einki varð sett á pláss. Upprunafílan {file} er óbroytt og liggur enn har. |
| `migrate.stoppedAt` | Stopped at: {step} | Stoppede ved: {step} | Steðgaði við: {step} |
| `migrate.unvBadge` | Not applied | Ikke anvendt | Ikki nýtt |
| `migrate.unvTitle` | Migration failed — no language tag after the re-check | Migrering mislykkedes — intet sprogmærke efter kontrollen | Flyting miseydnaðist — einki málmerki eftir kanningina |
| `migrate.unvBody` | The copy step reported success, but re-checking the new file found no language on {track}. The new file was not put in place, and the original {file} is untouched. | Kopieringen meldte succes, men kontrollen af den nye fil fandt intet sprog på {track}. Den nye fil blev ikke sat på plads, og originalen {file} er urørt. | Avritingin boðaði frá, at tað eydnaðist, men kanningin av nýggju fíluni fann einki mál á {track}. Nýggja fílan varð ikki sett á pláss, og upprunafílan {file} er óbroytt. |
| `migrate.stepDone` | finished | færdig | liðugt |
| `migrate.stepNoTag` | no language tag on {track} | intet sprogmærke på {track} | einki málmerki á {track} |
| `migrate.stepNot` | not done | ikke udført | ikki gjørt |
| `migrate.tryAgain` | Try again | Prøv igen | Royn aftur |
| `migrate.showLog` | Show job log | Vis joblog | Vís arbeiðsloggin |
| `migrate.offTitle` | Connection to the server lost | Forbindelsen til serveren er afbrudt | Sambandið við ambætaran er slitið |
| `migrate.offBody` | The conversion runs on the server and isn’t affected. Status updates when the connection returns. | Konverteringen kører på serveren og påvirkes ikke. Status opdateres, når forbindelsen er tilbage. | Umbroytingin gongur á ambætaranum og verður ikki ávirkað. Støðan dagførist, tá ið sambandið kemur aftur. |
| `migrate.lastKnown` | Last known: {step} | Sidst kendt: {step} | Seinast kent: {step} |
| `migrate.reconnecting` | Reconnecting… | Genopretter forbindelsen… | Knýtir samband aftur… |
| `migrate.loading` | Loading tracks… | Indlæser spor… | Lesur spor inn… |
