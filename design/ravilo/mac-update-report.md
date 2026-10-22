# Mac update — report (round `mac-update`, no phase number)

No phase number was given and none is written anywhere. Nothing in this package needs one; where a draft spec would refer to itself it would say `phase-NNN`.

Package: the directions canvas, its one sibling stylesheet, its one renderer, 58 PNGs. Fonts are the self-hosted woff2 files already in `app/fonts/` (relative, not new). No CDN, no links out.

## Files

- `ravilo/Mac Update - Directions.html` — 25167 bytes · 178 lines
  - first line: `<!doctype html>`
  - last line: `</html>`
- `ravilo/mac-update.css` — 23230 bytes · 224 lines
  - first line: `/* Mac update — the one stylesheet for "Mac Update - Directions.html". Canvas furniture (dark ink on the host's light gray) + the Mac's two families, Aurora (dark) and Daylight (light). Tokens mirror ravilo.css / desktop-directions.css. */`
  - last line: `.bnav .p .dot{position:absolute;top:2px;right:12px;width:10px;height:10px;box-shadow:0 0 0 2px var(--bg-2)}`
- `ravilo/mac-update.js` — 24636 bytes · 205 lines
  - first line: `/* Mac update — renderers for "Mac Update - Directions.html". Every [data-mu] mount is drawn here.`
  - last line: `})();`
- `ravilo/mac-update-report.md` — this file
- `ravilo/mac-update-png/` — 58 PNGs, 1 device pixel per CSS pixel, element captures of the canvas frames. Largest is 102281 bytes; none is over 192 KiB (196608 bytes).
- `mac-update-about-desktop-cannot-update-disk-image.png` — 51503 bytes
- `mac-update-about-desktop-cannot-update-temporary-copy.png` — 51654 bytes
- `mac-update-about-desktop-checking.png` — 44810 bytes
- `mac-update-about-desktop-dev-build.png` — 26595 bytes
- `mac-update-about-desktop-downloading.png` — 51415 bytes
- `mac-update-about-desktop-failed-download.png` — 49010 bytes
- `mac-update-about-desktop-failed-verify.png` — 53137 bytes
- `mac-update-about-desktop-handover.png` — 73185 bytes
- `mac-update-about-desktop-held-back.png` — 55633 bytes
- `mac-update-about-desktop-longest-da.png` — 50351 bytes
- `mac-update-about-desktop-longest-fo.png` — 50596 bytes
- `mac-update-about-desktop-offline.png` — 51829 bytes
- `mac-update-about-desktop-ready-light.png` — 67252 bytes
- `mac-update-about-desktop-ready-notice-light.png` — 54411 bytes
- `mac-update-about-desktop-ready-notice-rail.png` — 40464 bytes
- `mac-update-about-desktop-ready-notice.png` — 54033 bytes
- `mac-update-about-desktop-ready-playing-settings.png` — 53783 bytes
- `mac-update-about-desktop-ready-playing.png` — 63098 bytes
- `mac-update-about-desktop-ready.png` — 67287 bytes
- `mac-update-about-desktop-toggle-off.png` — 48762 bytes
- `mac-update-about-desktop-toggle-on.png` — 51443 bytes
- `mac-update-about-desktop-up-to-date.png` — 47732 bytes
- `mac-update-about-mobile-cannot-update-disk-image.png` — 67268 bytes
- `mac-update-about-mobile-cannot-update-temporary-copy.png` — 67153 bytes
- `mac-update-about-mobile-checking.png` — 58446 bytes
- `mac-update-about-mobile-dev-build.png` — 39448 bytes
- `mac-update-about-mobile-downloading.png` — 65497 bytes
- `mac-update-about-mobile-failed-download.png` — 63592 bytes
- `mac-update-about-mobile-failed-verify.png` — 68570 bytes
- `mac-update-about-mobile-handover.png` — 75482 bytes
- `mac-update-about-mobile-held-back.png` — 70967 bytes
- `mac-update-about-mobile-longest-da.png` — 65527 bytes
- `mac-update-about-mobile-longest-fo.png` — 67557 bytes
- `mac-update-about-mobile-offline.png` — 67261 bytes
- `mac-update-about-mobile-ready-light.png` — 84709 bytes
- `mac-update-about-mobile-ready-notice-light.png` — 30863 bytes
- `mac-update-about-mobile-ready-notice-profile.png` — 51878 bytes
- `mac-update-about-mobile-ready-notice.png` — 31248 bytes
- `mac-update-about-mobile-ready-playing-settings.png` — 80187 bytes
- `mac-update-about-mobile-ready-playing.png` — 62330 bytes
- `mac-update-about-mobile-ready.png` — 84709 bytes
- `mac-update-about-mobile-toggle-off.png` — 63560 bytes
- `mac-update-about-mobile-toggle-on.png` — 65615 bytes
- `mac-update-about-mobile-up-to-date.png` — 62265 bytes
- `mac-update-account-desktop-ready.png` — 36682 bytes
- `mac-update-account-desktop-today.png` — 33276 bytes
- `mac-update-account-mobile-ready.png` — 51548 bytes
- `mac-update-account-mobile-today.png` — 47801 bytes
- `mac-update-banner-desktop-ready-notice.png` — 55748 bytes
- `mac-update-banner-desktop-ready-playing.png` — 64807 bytes
- `mac-update-banner-mobile-ready-notice.png` — 35497 bytes
- `mac-update-banner-mobile-ready-playing.png` — 46791 bytes
- `mac-update-dot-desktop-ready-notice.png` — 50239 bytes
- `mac-update-dot-mobile-ready-notice.png` — 31248 bytes
- `mac-update-notes-desktop-ready.png` — 82534 bytes
- `mac-update-notes-link-desktop-ready.png` — 67287 bytes
- `mac-update-notes-link-mobile-ready.png` — 84569 bytes
- `mac-update-notes-mobile-ready.png` — 102281 bytes

PNG naming: `mac-update-<direction>-<family>-<state>.png`. Directions in file names: `about` (the recommended set: Block A + Notice 1 + Notes A), `account` (Block B), `dot` (Notice 2), `banner` (Notice 3), `notes-link` (Notes A shown on its own; same pixels as `about-*-ready` except where the bottom-bar dot differs), `notes` (Notes B). Families: `desktop`, `mobile`.

## Direction headings, character for character

- `Block A · Software update, at the top of Settings ▸ About`
- `Block B · The line inside Settings ▸ Account, where it ships today`
- `Notice 1 · A dot on Settings, and one row at the sidebar’s foot`
- `Notice 2 · Quieter: the dot alone`
- `Notice 3 · Louder: a strip across the top of every page`
- `Notes A · A What’s new link; Sparkle’s window shows the notes`
- `Notes B · The three notes inside the block`

## Recommendation (adopted by the owner, 2026-10-15)

Block A + Notice 1 + Notes A, carried through every state. Notice 2 (dot alone) survives state 5 best — it has nothing to remove — but cannot be read as “restart” from across the room. Notice 1 survives state 5 by replacing its *Restart* with “After playback” and going dashed; nothing moves. Notice 3 survives state 5 worst: it sits over every page, including the music page, and must change shape while music plays.

## Did the update block move out of Settings ▸ Account?

**Yes — decided by the owner, 2026-10-15.** It moves out of Settings ▸ Account into a block of its own, **Software update**, at the top of **Settings ▸ About** (above the server row; About already shows the version). This is a change to what ships and was adopted deliberately. Block B keeps it in Account, drawn as it ships today and as it would have to grow.

## States — drawn and skipped, by name

Drawn in both families (desktop = Settings window ▸ About, plus the main window for the notice; mobile = the phone layout's Settings ▸ About, Home and Profile):

1. Up to date — drawn (`up-to-date`)
2. Checking… — drawn (`checking`)
3. Available, downloading in the background, with progress — drawn (`downloading`)
4. Ready: Restart to update — drawn in the block (`ready`, `ready-light`) and as the app-wide notice: desktop sidebar (`ready-notice`, `ready-notice-light`), desktop rail (`ready-notice-rail`), mobile Home (`ready-notice`, `ready-notice-light`), mobile Profile page (`ready-notice-profile`)
5. Ready while something is playing — drawn: desktop sidebar while music plays (`ready-playing`), desktop Settings block while a film plays (`ready-playing-settings`), mobile Profile and mobile Settings while music plays (`ready-playing`, `ready-playing-settings`); plus Notice 3 for comparison (`banner-*-ready-playing`)
6. Held back by the server — drawn (`held-back`)
7. Failed — drawn twice: the download (`failed-download`) and the signature (`failed-verify`), each with Try again
8. Cannot update from here — drawn twice: temporary copy (`cannot-update-temporary-copy`) and disk image (`cannot-update-disk-image`); shipped heading and sentence, Quit Ravilo only, toggle removed
9. The automatically-download toggle — drawn on (`toggle-on`) and off (`toggle-off`)
10. Development build — drawn as absent (`dev-build`): the block is not rendered at all
11. The longest string, in Danish and in Faroese — drawn (`longest-da`, `longest-fo`). The longest string is `su_move_tmp`: 138 characters in Danish, 145 in Faroese
12. Offline — drawn (`offline`), no Check now

Standing set:
- loading → state 2, drawn
- error → states 7 and 12, drawn
- longest string, Danish and Faroese → state 11, drawn
- offline → state 12, drawn
- **empty → not applicable**: the block is never empty; state 1 is its resting shape. Not drawn.
- **10-foot focus → not applicable**: no TV surface is in this round. Not drawn.

Skipped: nothing else.

Also drawn: the handover point (`handover`, both families) — Ravilo’s last line, “Closing Ravilo to install 1.53…”, and a dashed placeholder where Sparkle’s own window takes over. Sparkle’s windows are not drawn.

## Decisions — decided by the owner, 2026-10-15: all four leans

1. Where the block lives — **decided: Block A** (Settings ▸ About). The block moves out of Settings ▸ Account. Alternative drawn: Block B (Account), as shipped and grown.
2. The ready notice — **decided: Notice 1** (dot on Settings + one row at the sidebar’s foot; dot on Profile + row atop the Profile page on mobile). Quieter alternative: Notice 2. Louder alternative: Notice 3. Survives state 5 best: Notice 2; worst: Notice 3.
3. Release notes — **decided: Notes A** (What’s new link; Sparkle’s window shows the notes). Alternative drawn: Notes B with the three stand-in bullets.
4. *Check for Updates…* — **decided: yes**. Position: in the *Ravilo* app menu directly under *About Ravilo*, above the separator before *Settings…* (the standard macOS position). Strings: en `Check for Updates…` · da `Søg efter opdateringer…` · fo `Leita eftir dagføringum…`. Not drawn.

## Still open — drawn one way, not covered by the four decisions

- **Sparkle’s download-progress window.** Drawn: every download is a background download shown in Ravilo’s block, including the one started by *Download* with the toggle off; Sparkle’s windows begin at *Restart to update*. If Sparkle’s progress window is wanted for the manual Download, that becomes a second handover point.
- **What × on the notice row hides.** Drawn: the row until the next launch; the dot stays until installed.
- **The Profile dot is shared.** The phone’s Profile entry already carries the support lane’s unread dot. Drawn: one dot, “your Profile page has something”; the page’s first row says which. On mobile Home, Notice 1 and Notice 2 therefore look identical (`about-mobile-ready-notice.png` and `dot-mobile-ready-notice.png` are the same size, 31248 bytes, and draw the same pixels); they differ only on the Profile page.
- **The shipped “where to install” sentence under Download** was not available to this project. Block B’s today frame shows a stand-in (`su_ship_install`); the shipped sentence wins.
- **Shipped da/fo for the Applications strings** were not available here. The da/fo for `su_move_title`, `su_move_tmp`, `su_move_dmg` are drafts; Danish uses the Mac’s own Danish folder name *Programmer*, Faroese keeps *Applications* in English. Any shipped translation wins.
- `su_try_again` has the same words as the support lane’s `sup_try_again`; one key may serve both.

## Every new UI string (en / da / fo)

Reused unchanged from the shipped files, not new: `settings`, `account`, `about`, `signed_in_as`, `pw_change`, `pm_profile`. The drawing-only stand-ins “Music is playing”, “stand-in · no title” are canvas descriptions, not product strings.

- `su_title`
  - en: Software update
  - da: Softwareopdatering
  - fo: Ritbúnaðardagføring
- `su_version`
  - en: Ravilo {v}
  - da: Ravilo {v}
  - fo: Ravilo {v}
- `su_uptodate`
  - en: Up to date
  - da: Opdateret
  - fo: Dagført
- `su_checked`
  - en: Checked today at {t}
  - da: Tjekket i dag kl. {t}
  - fo: Kannað í dag kl. {t}
- `su_check_now`
  - en: Check now
  - da: Tjek nu
  - fo: Kanna nú
- `su_checking`
  - en: Checking for updates…
  - da: Søger efter opdateringer…
  - fo: Leitar eftir dagføringum…
- `su_downloading`
  - en: Ravilo {v} is downloading
  - da: Ravilo {v} hentes
  - fo: Ravilo {v} verður tikið niður
- `su_progress`
  - en: {a} of {b} MB
  - da: {a} af {b} MB
  - fo: {a} av {b} MB
- `su_dl_sub`
  - en: It downloads in the background. Ravilo asks before it restarts.
  - da: Den hentes i baggrunden. Ravilo spørger, før den genstarter.
  - fo: Tað verður tikið niður í bakgrundini. Ravilo spyr, áðrenn tað byrjar umaftur.
- `su_available`
  - en: Ravilo {v} for Mac is available
  - da: Ravilo {v} til Mac er tilgængelig
  - fo: Ravilo {v} til Mac er tøkt
- `su_size`
  - en: {n} MB
  - da: {n} MB
  - fo: {n} MB
- `su_download`
  - en: Download
  - da: Hent
  - fo: Tak niður
- `su_ready`
  - en: Ravilo {v} is ready
  - da: Ravilo {v} er klar
  - fo: Ravilo {v} er klárt
- `su_ready_sub`
  - en: Restart Ravilo to finish. It takes a few seconds.
  - da: Genstart Ravilo for at gøre det færdigt. Det tager få sekunder.
  - fo: Byrja Ravilo umaftur fyri at gera liðugt. Tað tekur fáein sekund.
- `su_restart`
  - en: Restart to update
  - da: Genstart for at opdatere
  - fo: Byrja umaftur og dagfør
- `su_whats_new`
  - en: What’s new
  - da: Nyheder
  - fo: Hvat er nýtt
- `su_wait_sub`
  - en: Something is playing, so the update waits. Restart to update appears here when it stops.
  - da: Der afspilles noget, så opdateringen venter. Genstart for at opdatere vises her, når det stopper.
  - fo: Okkurt verður spælt, so dagføringin bíðar. Byrja umaftur og dagfør kemur her, tá ið tað steðgar.
- `su_held`
  - en: Ravilo {v} is out
  - da: Ravilo {v} er udkommet
  - fo: Ravilo {v} er komið út
- `su_held_sub`
  - en: Your server runs {sv}, so this Mac waits for the server. It updates once the server has {v}.
  - da: Din server kører {sv}, så denne Mac venter på serveren. Den opdateres, når serveren har {v}.
  - fo: Ambætarin hjá tær koyrir {sv}, so henda Mac bíðar eftir ambætaranum. Hon verður dagførd, tá ið ambætarin hevur {v}.
- `su_fail_dl`
  - en: The update didn’t download
  - da: Opdateringen blev ikke hentet
  - fo: Dagføringin varð ikki tikin niður
- `su_fail_dl_sub`
  - en: Check that this Mac is online, then try again.
  - da: Tjek, at denne Mac er online, og prøv igen.
  - fo: Kanna, at henda Mac er á netinum, og royn so aftur.
- `su_fail_ver`
  - en: The update couldn’t be confirmed
  - da: Opdateringen kunne ikke bekræftes
  - fo: Dagføringin kundi ikki váttast
- `su_fail_ver_sub`
  - en: Ravilo installs only an update it can confirm is genuine. Try again to download it fresh.
  - da: Ravilo installerer kun en opdatering, den kan bekræfte er ægte. Prøv igen for at hente den på ny.
  - fo: Ravilo leggur bert eina dagføring inn, sum tað kann vátta er ekta. Royn aftur fyri at taka hana niður av nýggjum.
- `su_try_again`
  - en: Try again
  - da: Prøv igen
  - fo: Royn aftur
- `su_move_title`
  - en: Move Ravilo to your Applications folder
  - da: Flyt Ravilo til mappen Programmer
  - fo: Flyt Ravilo í Applications-mappuna
- `su_move_tmp`
  - en: Ravilo is running from a temporary copy, which can’t reach your home network. Quit Ravilo, drag it to your Applications folder, and open it from there.
  - da: Ravilo kører fra en midlertidig kopi, som ikke kan nå dit hjemmenetværk. Afslut Ravilo, træk den til mappen Programmer, og åbn den derfra.
  - fo: Ravilo koyrir frá einum fyribilsavriti, sum ikki røkkur heimanetið hjá tær. Lat Ravilo aftur, drag tað í Applications-mappuna og opna tað haðani.
- `su_move_dmg`
  - en: Ravilo is running from the disk image, which can’t reach your home network. Quit Ravilo, drag it to your Applications folder, and open it from there.
  - da: Ravilo kører fra diskbilledet, som ikke kan nå dit hjemmenetværk. Afslut Ravilo, træk den til mappen Programmer, og åbn den derfra.
  - fo: Ravilo koyrir frá diskmyndini, sum ikki røkkur heimanetið hjá tær. Lat Ravilo aftur, drag tað í Applications-mappuna og opna tað haðani.
- `su_quit`
  - en: Quit Ravilo
  - da: Afslut Ravilo
  - fo: Lat Ravilo aftur
- `su_offline`
  - en: Can’t check while offline
  - da: Kan ikke søge, mens du er offline
  - fo: Kann ikki kanna uttan samband
- `su_offline_sub`
  - en: Last checked today at {t}. Ravilo checks again when this Mac is back online.
  - da: Senest tjekket i dag kl. {t}. Ravilo søger igen, når denne Mac er online igen.
  - fo: Seinast kannað í dag kl. {t}. Ravilo kannar aftur, tá ið henda Mac er á netinum aftur.
- `su_auto`
  - en: Download updates automatically
  - da: Hent opdateringer automatisk
  - fo: Tak dagføringar niður sjálvvirkandi
- `su_auto_on`
  - en: Ravilo still asks before it restarts.
  - da: Ravilo spørger stadig, før den genstarter.
  - fo: Ravilo spyr framvegis, áðrenn tað byrjar umaftur.
- `su_auto_off`
  - en: Ravilo tells you when an update is out and downloads it when you say so.
  - da: Ravilo fortæller, når der er en opdatering, og henter den, når du beder om det.
  - fo: Ravilo sigur frá, tá ið ein dagføring er komin, og tekur hana niður, tá ið tú biður um tað.
- `su_closing`
  - en: Closing Ravilo to install {v}…
  - da: Lukker Ravilo for at installere {v}…
  - fo: Letur Ravilo aftur fyri at leggja {v} inn…
- `su_closing_sub`
  - en: macOS takes it from here and opens Ravilo again.
  - da: macOS tager over herfra og åbner Ravilo igen.
  - fo: macOS tekur við haðani og opnar Ravilo aftur.
- `su_notice`
  - en: Update ready
  - da: Opdatering klar
  - fo: Dagføring klár
- `su_notice_restart`
  - en: Restart
  - da: Genstart
  - fo: Byrja umaftur
- `su_notice_wait`
  - en: After playback
  - da: Efter afspilning
  - fo: Aftaná avspæling
- `su_hide`
  - en: Hide
  - da: Skjul
  - fo: Fjal
- `su_notes_title`
  - en: New in {v}
  - da: Nyt i {v}
  - fo: Nýtt í {v}
- `su_n1`
  - en: Faster start on the music tab
  - da: Hurtigere start på musikfanen
  - fo: Skjótari byrjan á tónleikateiginum
- `su_n2`
  - en: Subtitles keep their size after a restart
  - da: Underteksterne beholder deres størrelse efter en genstart
  - fo: Undirtekstirnir halda stødd síni eftir eina umbyrjan
- `su_n3`
  - en: A clearer message when the server can’t be reached
  - da: En tydeligere besked, når serveren ikke kan nås
  - fo: Greiðari boð, tá ið ambætarin ikki røkkst
- `menu_check`
  - en: Check for Updates…
  - da: Søg efter opdateringer…
  - fo: Leita eftir dagføringum…
- `su_row_version`
  - en: Version
  - da: Version
  - fo: Útgáva
- `su_row_server`
  - en: Server
  - da: Server
  - fo: Ambætari
- `su_ship_install`
  - en: Open the download and drag Ravilo to your Applications folder.
  - da: Åbn overførslen, og træk Ravilo til mappen Programmer.
  - fo: Opna niðurtøkuna og drag Ravilo í Applications-mappuna.

## Existing files edited in place

None. Every file in this package is new. No existing file in the project was modified.

## Confirmation

I created nothing called `STATUS.md` and nothing called `CLAUDE.md`, and nothing under `specs/` or `scripts/`.
