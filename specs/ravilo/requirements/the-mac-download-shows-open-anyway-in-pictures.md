# Ravilo for Mac shows both *Open Anyway* steps in pictures — on the download page and in the `.dmg` window

Task: DEB-1469

> Owner, 2026-10-16 (DEB-1076), after the personal-Mac walk: *"the tests were maybe good, but the user experience was
> really bad … I have to click "Done" and I just know that I have to open system settings and find "Open anyway"
> hidden in there."* He was asked twice whether to notarise. The second time he was told that the approval is
> needed twice for every download, not once per Mac. Both times he answered: *"Still no Apple bills: show both Open
> Anyway steps in pictures, on the download page and in the .dmg window."*

## Why there are two steps

Ravilo for Mac is signed with our own certificate and is not notarised (R331 D2). Gatekeeper treats it as coming from
an unidentified developer. The 2026-10-16 walk (DEB-763: macOS 27.0.1, Gatekeeper on, a `.dmg` downloaded with a
browser and dragged with Finder) found that **every hand-downloaded version is refused twice**. The disk image is
refused first. After the drag, the installed app is refused too. Each refusal is a dialog whose buttons are *Move to
Trash* and *Done*, and the only way past is System Settings ▸ Privacy & Security ▸ ***Open Anyway***. The second
approval also asks for the user's password. v1.51 over an approved v1.50 did all of this again (R331, amendment
2026-10-16).

Today, all a user is told is one line in the Mac card of the public download page,
`jellystructure.jebster.net/ravilo.html`: *"Not notarised. The first time: System Settings → Privacy & Security → Open
Anyway."* It names one step, not two. It says "the first time", which is false. And it is text, so the button stays
hidden.

## Scope

Two surfaces: the Mac card on the public download page, and the window the `.dmg` opens to. Both are
explanations, not changes to signing. **Not in scope:** notarisation or any Apple account (owner, 2026-10-16, twice);
release notes (FR-R331-6 stays withdrawn: *"People downloading dmg already know how to install a dmg"*); Sparkle's
in-app update path (R386, open on DEB-1292); the Local Network card's findings from the same walk.

**This reopens R385 D4's site half.** On 2026-10-12 the owner kept the install steps off the download page (*"the
in-app line is enough; leave the site alone"*). The 2026-10-16 answer above moves that, and R385 records it in an
amendment. Its message for a translocated copy still stands as the fallback for a user who skips the pictures.

## Requirements

**FR-1 — Five steps, in the order a user meets them.** One sequence, used on both surfaces:

1. **Open the downloaded `.dmg`.** macOS refuses it. Choose ***Done***, not *Move to Trash*.
2. **System Settings ▸ Privacy & Security**, scroll to *Security*, choose ***Open Anyway*** next to the Ravilo line.
   The image opens.
3. **Drag Ravilo onto Applications.**
4. **Open Ravilo from Applications.** macOS refuses it again. Choose ***Done***.
5. **Privacy & Security ▸ *Open Anyway*** once more, and confirm with your password. Ravilo opens.

Each step has a picture and one short sentence. Button names are macOS's own, in bold, exactly as the OS shows them.

**FR-2 — It says it happens on every download.** The page says plainly that these steps repeat for every version
downloaded from the page, because Ravilo is not registered with Apple. It does not say "the first time". It says
nothing about updates from inside the app until R386's quarantine-free path is proven (DEB-1292). When it is, this
spec gains a dated amendment and the page gains one line.

**FR-3 — The download page.** The Mac card's one-line `dl-note` becomes the five-step strip (FR-1) with FR-2's
sentence above it. The *Download .dmg* button stays where it is and stays the card's primary action. The strip can be
collapsed under a *How to open it* toggle, but it must be **open by default**, since everyone who downloads needs
it. Every picture has alt text that carries the whole instruction, so the page still works when images don't load.
The strip reads at the page's phone width as well as at desktop width. The page's existing style is kept.

**FR-4 — The `.dmg` window.** The image opens to a fixed-size Finder window whose background shows:

- the Ravilo icon and the Applications shortcut, placed exactly over drawn targets, with an arrow from one to the
  other (step 3);
- steps 4 and 5, under the arrow.

Steps 1 and 2 are not drawn here: by the time the window is open, they are behind the user. The background is an image
at 1× and 2×. It must read in both Light and Dark appearance, because a Finder window's background image does not
change with the appearance.

**FR-5 — The pictures are drawn, never screenshots.** A screenshot of a real Mac shows a real user name, other apps in
Privacy & Security, and a macOS version that will age. Drawn pictures show a stand-in: a dialog, a settings pane and a
password prompt with no name. Their text is the macOS wording recorded on the walk: *Move to Trash*, *Done*, *Privacy
& Security*, *Open Anyway*, and a password. **The dialog titles were not recorded on the walk.** Verification
(acceptance 3) confirms them on a real Mac. If macOS words differ, the copy changes, not the design.

**FR-6 — Nothing that weakens the Mac.** Neither surface tells the user to run `xattr`, to disable Gatekeeper, or to
use `spctl`. On a work-managed Mac, *Open Anyway* may ask for an administrator's password the user does not have (seen
on 2026-10-16). The page says so in one line, so that a user stuck there knows it is their Mac's policy, not Ravilo.

**FR-7 — The `.dmg` is still one file with one signature.** The background and the window layout go into the image
before the appcast's EdDSA signature is computed (R386 FR-R386-2). The appcast signs the final bytes. R331's
`codesign --verify --deep --strict` and its self-test are unchanged, because the app inside is untouched.

## What the code makes cheap or expensive

- **The page** is plain HTML/CSS in the hosting tree (`~/jellystructure/site/ravilo.html`, `assets/`). It is not in
  this repository. A change there goes live only with `docker compose up -d --build site`, which is a deploy, and
  deploys are not the engineer's to run.
- **The `.dmg` window**: Compose's `nativeDistributions` DSL has no setting for a DMG background. There are two
  routes, and both run on the `macos-15` runner in `deploy-macos.yml`:
  - give `jpackage` a resource directory holding `Ravilo-background.tiff` and `Ravilo-dmg-setup.scpt`;
  - rebuild the image after packaging with `hdiutil`.

  The engineer picks one. FR-7 fixes the ordering either way.

## Acceptance

1. On a Mac with Gatekeeper on, using a `.dmg` built with this change and **downloaded with a browser** (never
   `scp`, which drops the quarantine flag), the image opens to the drawn window. The icon and the Applications
   shortcut sit on their drawn targets in Light and in Dark appearance.
2. A person who follows only the pictures gets from the downloaded file to an open Ravilo, through both refusals.
3. The dialog and settings wording on the Mac under test matches the pictures. Any difference is recorded with the
   macOS version.
4. The download page shows the strip open by default, readable at phone and desktop widths, with alt text on every
   picture and no "the first time" left anywhere on it.
5. `check-deanonymization.sh` is green, and no picture carries a real name, a library title or another app.

## Amendment (2026-10-22): the owner's picks from the drawn round, and the window's numbers (DEB-1076, DEB-1470)

Round 8 was drawn in the repo without the design tool (lane C, `design/incoming/2026-10-22-mac-open-anyway/`, at
`afd5623b` plus revision `75edecfc`). The owner reviewed it on DEB-1076 (card `ece9c6f7`) and took all three
recommendations: *"A, the strip below the cards, with pictures at readable size"*, *"always open, no fold"*, and the
`.dmg` window *"as drawn"*. FR-3 is replaced. FR-4 gains the numbers it was waiting on.

**FR-3, replaced — the strip sits below the download cards, always open.**
- **The Mac card keeps its shape.** Its one-line `dl-note` becomes a pointer, not the instructions: *"Not notarised.
  macOS asks twice on every download — the five steps are below."* *Download .dmg* stays the card's primary action,
  where it is.
- **Below the three download cards, at full content width,** a section titled *Opening Ravilo on a Mac* carries
  FR-2's sentence and then the five steps of FR-1: two columns at desktop width, one at phone width, each step a
  picture beside its sentence. The FR-6 line about work-managed Macs closes the section.
- **No fold and no toggle.** The steps are always shown. (Direction B, the steps inside the Mac card, was drawn and
  rejected: at the card's width every picture shrinks to 96px and stops showing anything.)
- Alt text on every picture carries the whole instruction, as before. Step sentences name no version (*"Open the
  .dmg you downloaded"*), because the page outlives any one release; the card's meta line already shows the version.
- **The drawing is the reference.** `page.html` and `mac-open-anyway.css` in the round directory, and the attached
  renders on DEB-1470 (`mac-open-anyway-a-desktop.png`, `-a-phone.png`). The page's existing style and tokens are kept.

**FR-4, the numbers.** The window's content area is **720 × 520 pt**. The Ravilo icon is centred at **(190, 190)** and
the Applications shortcut at **(530, 190)**, each with a label band of **190 × 34 pt from y = 252**. Below a divider,
steps 4 and 5 sit side by side, each a picture at 236 px design width with its sentence beneath. The background is
delivered at **1× (720 × 520)** and **2× (1440 × 1040)**, and it is identical in Light and Dark appearance. Source:
`dmg.html` and `dmg-window.html`; bare assets are `mac-open-anyway-dmg-bg-1x.png` and `-2x.png` on DEB-1470. The
engineer places the icons from these numbers, not by eye.

**Renders.** The round's `render.mjs` is the render command lane C recorded, and it is what produces the background
PNGs. Whether the build renders the background at package time or commits the PNG is the engineer's call. Either way,
FR-7's ordering holds.
