# Put Ravilo on a Samsung TV from the Android phone (experimental)
Task: DEB-4843

## Scope

Owner approved the plan in DEB-13 on 2026-11-17 (comment `79fe59e2`): *"implement all of this, but only after all the already Tizen work."* This file is the Android half: the Profile row, the guide, and the `sdb` installer. The server half — request table, routes, admin page, Ready detection — is `samsung-tv-install-requests.md` (DEB-4842, done) and is used as written. DEB-4844 is device QA. Nothing here changes the wire.

The guide is the part the owner cares about most (2026-11-17 00:20): easy, good-looking, no more information than needed, for people who have never used Developer Mode or typed an IP address. The English copy in `i18n/en.json` is a **draft the owner reviews from screenshots before this merges to `main`**.

Android phone build only. Not a TV, not iPhone, not the desktops, not the web.

## Requirements

**FR-1 · The row.** Profile → Account card gets *Samsung TV* with a green *Experimental* badge, directly after *Sign in a TV* and before *Settings*. It is a nullable parameter on `ProfileScreen`, like `downloads`: null (no row at all) on every platform but an Android phone, so a TV, Tizen, the web and the desktops never show it. Tapping opens the guide.

**FR-2 · The TV is found before step 1.** The guide starts with an intro (*Start*) that outlines the whole path in four very short lines on the one screen — turn on Developer Mode, send the request, wait for approval (said to take a few days, not minutes), then install from this phone once approved and a new release is out (owner, 2026-11-23) — and then looks for the TV on **the phone's own Wi-Fi subnet only** (the /24 of its Wi-Fi address, up to 254 hosts, concurrent requests with a short timeout, no internet round-trip, nothing outside the subnet). A host counts as a Samsung TV when `http://<ip>:8001/api/v2/` answers JSON with `device.modelName`; the answer works with Developer Mode off. From it the guide reads model name, OS version, friendly name, `developerMode` and `developerIP`. One TV found is offered as *This is my TV*; several are a list; none is one sentence (*Turn the TV on and connect this phone to the same Wi-Fi*) with *Try again*. Without a Wi-Fi address (mobile data, no network) the intro says so in one sentence and offers no scan.

**FR-3 · The steps, for that TV.** Four numbered steps, one action and one big primary button each, a step indicator (*Step 2 of 4*), a drawn illustration in the Ravilo theme (our own vector drawings: remote with 1 2 3 4 5 lit, the Developer mode switch, the power button — no photos, no Samsung screenshots), and under about 40 words of body text:
1. *Open the hidden menu* — **Apps**; on a 2024-or-later TV (Tizen 8+) *then scroll down to **App Settings***; then use the remote's **123** key to bring up number keys and enter 1 2 3 4 5 — and say plainly not to type in the search box (owner, 2026-11-23). One hint line for a remote that has real number keys.
2. *Turn on Developer Mode* — switch it **On**; in the box below it type the phone's own Wi-Fi address, shown large with its digits spaced for reading off a screen; press OK. The user never types an address into the phone.
3. *Restart the TV* — hold the power button until the Samsung logo appears; a quick press is not enough.
4. *Ask for access* — send the request (FR-6).

"Developer Mode" is the only technical term on screen. "Host PC IP" is *the box below it*. Troubleshooting is a quiet *It didn't work?* link on the step it belongs to; step 2's lists the Arabic/Hebrew reversed-address case, step 3's the unplug-for-a-minute case. A failed check is one sentence and one button back to the step that fixes it, never a list. Connection, `sdb` and progress detail goes to the log, never the screen. Light and dark, readable at large font sizes.

**FR-4 · Auto-checks.** After step 3 (the restart — Developer Mode is not on before it) and again before any install, without a button: re-read `:8001/api/v2/` and compare with the phone. Developer Mode off → *go back to step 1*; Host PC IP differs from the phone's address → one sentence naming both, *go back to step 2*; TV not answering → *Turn the TV on*. The model year, read from the letter in the TV's model code, picks the step-1 path (2024 and later: *App Settings*) and the Tizen version sent with the request; an unreadable code gets the shorter path and "unknown". A returning user (request exists, Ready, or Update) skips straight to *Install on TV* and is sent back only through the step the check names.

**FR-5 · The `sdb` client.** A Kotlin implementation of the Samsung debug bridge over TCP 26101 (ADB-framed: CNXN/OPEN/OKAY/WRTE/CLSE, 24-byte little-endian headers, `data_check` = byte sum, magic = command xor 0xFFFFFFFF), with `shell` and sync `push`. Framing and sequencing live in `commonMain` over a small byte-transport interface so `:ravilo-ui:desktopTest` covers them against a fake TV; the TCP socket is the only Android-only piece. The quirks measured on the real 2019 TV (DEB-4783) are rules: **one command per fresh connection** (a second command on a connection hangs the TV's `sdbd`); push and `vd_appinstall` on separate connections with a wait between; every command under a timeout; output is checked, never an exit status; the raw answer to every command goes to the log. The device id is read from `shell 0 getduid` leniently — the first token of 8–32 uppercase letters or digits in the answer, ignoring prompts and whitespace — and an answer with none is the sentence *this TV did not give its ID*, with the raw answer logged. Model, year and Tizen version come from the REST answer. Install is: push the `.wgt` to the TV's temp directory, then `vd_appinstall <package id> <path>`, then `was_execute <app id>`.

**FR-6 · Request and states.** After step 4 passes its check, the phone sends `POST /api/tv/samsung-requests` with the DUID, model, Tizen version and TV name, and shows *Request sent*. The row's later screens render what `GET /api/tv/samsung-requests` says, re-read each time the guide opens: Requested (waiting, may take a while), Approved (waiting for a release that covers the TV), Ready (*Your TV is approved*, button *Install on TV*), Rejected (the owner's note if any, one button to ask again), Withdrawn. A Requested row can be withdrawn. The phone never infers a state.

**FR-7 · Install.** *Install on TV* (and *Update* / *Reinstall* on a done install) runs the FR-4 checks, downloads the row's `wgt_url`, verifies before pushing that the package's distributor certificate (`signature1.xml`, `URN:tizen:deviceid=`) carries this TV's DUID — a package that does not cover the TV is never pushed — then pushes, installs and launches (FR-5), with one progress bar (*Downloading → Sending to TV → Installing*). The final screen is *Ravilo is on your TV* — *find it under Apps*. Each failure is one sentence and one button; the log carries the detail.

**FR-8 · Copy.** Every string is a key in `i18n/en.json`, `da.json` and `fo.json` under `samsungtv.*`, through `ravilo-i18n`; no string names Jellyfin. The English draft follows the plan's copy table.

**FR-9 · Verification.** `:ravilo-ui:desktopTest` covers the sdb framing and sequencing against a fake TV (one command per connection, lenient DUID parse, push chunking, error paths), the subnet and REST parsing, the guide's state machine (every transition, every auto-check branch, the returning-user shortcut) and the `.wgt` certificate check against a frozen fixture. `:ravilo-ui:testDebugUnitTest` covers the row's position and absence. Not coverable automatically, and so on the QA ticket — the `shell 0 …` service string and the sync framing are written from the ADB protocol and the 2019 TV's measured quirks, and the first real answer from a TV or the emulator may differ: real TCP to the TV's `sdbd`, the Wi-Fi address, the scan on a real network, and the install itself — on the Pixel 7 against the Tizen 10 emulator (DEB-4844), then the owner on his real TV. **Design gate:** screenshots of every guide screen, light and dark, are shown to the owner on this task and signed off before the work is merged to `main`. The Tizen 10 emulator's clock moved past 2027-11-16 — does the installed app still launch — is recorded here by QA.

## Amendment — 2026-11-23 (DEB-5409): no waiting state for a TV already covered, and one screen that shows where every TV stands

The owner ran scan → request → approve himself on the real Samsung on DEB-5356 (15:51Z): *"It worked. But let's make it more nice looking after the device has been approved. So it's clear that we find 1-2 TVs on this network and that the one TV has already been approved. So we can see it's current version and what the latest version is."* Three minutes earlier, of the screen his approved TV showed: *"'Approved, waiting for release' no need for this state … as this device is already approved."* His TV was already in the distributor certificate. It showed *Approved* only because the server compares approved rows with the release on its hourly check. The server half of the fix is the amendment of the same date in `samsung-tv-install-requests.md`: the owner still approves every TV, and an approved TV the current release already covers is Ready at once. This amendment is the phone half.

**What it changes.**
- **FR-2, intro line 4** becomes *install it on the TV from this phone once approved*. It no longer mentions a new release, because for most TVs there is no wait.
- **FR-6.** *Approved* stays as a screen state, but only for a TV the current release does not cover yet. Its body says Ravilo for this TV comes with the next release. An Approved or Ready row no longer opens a screen of its own: it opens the overview (FR-10). Requested and Rejected open as before.
- **FR-7.** The Done screen's *Done* goes back to the overview, which now shows the version just installed.

**FR-10 · The overview: the TVs on this Wi-Fi, and where each one stands.** When the viewer has at least one Approved or Ready request, opening the guide (and *Done* after an install) shows one screen, *Your Samsung TVs*. It renders these, in this order:
1. **Finding the TVs.** The screen starts the FR-2 scan of the phone's own subnet by itself and says *Looking for TVs on this Wi-Fi…* until the scan ends. Without a Wi-Fi address it says FR-2's sentence and shows only the server rows (item 4).
2. **One card per TV found**, at most a handful. Each card shows the TV's friendly name and model, with a badge from the viewer's request for that TV:
   - *Approved*, green, for Approved or Ready;
   - *Waiting for approval* for Requested;
   - *Not approved* for Rejected;
   - *Not set up* when the viewer has no request for that TV.
3. **The approved TV's card** has two lines more: *On the TV: 1.70* (FR-11) and *Latest: 1.71*. *Latest* is the row's `ready_from_release`, which the server keeps at the newest release covering the TV. A Ready card's one primary button depends on what the TV has:
   - *Install on TV* when Ravilo is not on it, or its version is not known;
   - *Update to 1.71* when the TV's version is older;
   - no button, the line *Up to date*, and a quiet *Reinstall* link when the versions are equal.

   Every button runs FR-7 unchanged, including its FR-4 checks and its DUID check. An Approved (not yet covered) card shows *Comes with the next Ravilo release* in place of *Latest* and has no button. A *Not set up* card has a quiet *Set up this TV* link, which opens FR-3 step 1 for that TV (a new request under DEB-4842's ten-row cap). A *Not approved* card shows the owner's note, if any, and *Ask again*.
4. **A request whose TV the scan did not find** gets a card from the server row: name and model, its badge, *Not found on this Wi-Fi*, and no version and no button. Below the list, one quiet line says how many TVs were found, with *Search again*.

**Which TV a request belongs to.** A found TV matches a request when its `modelName` and `name` are equal to the row's `model` and `name`. Both came from that TV's own `:8001/api/v2/` answer when the request was sent. When two found TVs match the same row, for example two sets of one model that both keep the factory name, the phone does not guess. Neither card gets the badge. The row gets its own card (item 4) with *Install on TV*, and FR-7's DUID check picks the right TV. A TV renamed since the request appears as *Not set up*, next to its row's *Not found on this Wi-Fi* card. That is accepted, because the install still checks the DUID. The phone never infers a state: badges come from the last `GET /api/tv/samsung-requests`, versions from the TV.

**FR-11 · The installed version is read from the TV.** During the scan the phone asks each TV that answered FR-2 for `GET http://<ip>:8001/api/v2/applications/RaviloScrn.RaviloScrn`, with the same short timeout. The answer is one of three:
- a JSON object with a `version` gives the installed version (`1.71.0` is shown as `1.71`);
- `404`, or a JSON object with `code` 404, means *not installed*;
- anything else means *not known*.

When the TV's answer is *not known*, the phone uses the release tag it installed on that DUID itself last, which it keeps in local preferences per DUID after a successful FR-7. Without that, the version stays *not known*, and the line reads *On the TV: —*. The release workflow stamps the `.wgt`'s `config.xml` version as `MAJOR.MINOR.0` from the tag (`deploy-tizen-tv.yml`). Versions compare as major and minor only, so `v1.71` and `1.71.0` are equal. **Measured on the real 2019 set (Tizen 5.0) on 2026-11-24 (DEB-5410).** The route answers without pairing or a token, from an address other than the TV's Host PC IP. Ravilo gives `200` `{"id":"RaviloScrn.RaviloScrn","name":"Ravilo","running":false,"version":"1.72.0","visible":false}`, and a missing app gives `404` with the body `{"code":404,"message":"Not found error.","status":404}`. `running` and `visible` read `false` for an app installed by a developer even while it is usable, so the phone reads only `version` and the 404, never those two. The Tizen 10 emulator does not expose port 8001 to this host, so a 2023-or-later set is unmeasured. On such a set the fallback above applies. This needs no Developer Mode and adds no request to our server.

**FR-12 · Design and verification.** The owner chose on DEB-5409 (2026-11-23) that **Rannvá draws the overview first**: a phone design round, owner's pick from its pictures, before any Kotlin. Its picked direction is written back into FR-10 as a dated amendment, and the build follows it. FR-9's gate still applies afterwards: before the work merges to `main`, the owner signs off screenshots on DEB-5409 in light and dark, at a large font, in `da` and `fo`. These states are covered by `GuideScreenshotsTest`:
- one TV, approved and up to date;
- one TV approved with an update waiting, next to a second TV marked *Not set up*;
- an approved TV not yet in a release;
- an approved TV not found on this Wi-Fi;
- no Wi-Fi.

`:ravilo-ui:desktopTest` covers:
- the match rule, including the two-identical-TVs case and a renamed TV;
- the three FR-11 answers and the local fallback;
- the version comparison (`v1.71` against `1.70.0`, `1.71.0` and a version that is not known);
- `open()` landing on the overview for Approved and Ready, and on the old screens for Requested and Rejected.

QA on DEB-5409's ticket: the real Samsung and the Tizen 10 emulator answer FR-11's route (record the raw answer for an installed and a missing app), and the owner's set shows *Approved* and the right two versions. **No wire change.** The phone reads fields the viewer route already returns. An installed v1.72 phone keeps its old screens and simply sees Ready sooner.

## Amendment — 2026-11-24 (DEB-5504): step 1 asks which remote the viewer has

The owner, on DEB-5504, attached a photo of a basic Samsung remote with physical 0–9 keys and no *123* key: *"We need better samsung tv guidelines as there are two types of remotes and this remote doesnt have the 123 button."* Step 1 is written for the Smart Remote. A remote with number keys gets only FR-3's hint line, under the body, in the quietest text on the screen. Samsung's newer Smart Remotes also don't label the key *123*: it's the **Number/Color** button, marked with coloured dots. The phone can't tell which remote a TV has, because the basic remote ships with every model year. So the viewer says which one they have. The owner picked this on card `e711cdd2` (2026-11-24): *Ask which remote*.

**FR-13 · Step 1 asks which remote.** Step 1 (*Open the hidden menu*) is still *Step 1 of 4*. It no longer opens on an instruction. It opens on the question *Which remote do you have?*, answered by tapping one of two drawn remotes:
- ***It has number keys*** — a long remote with a 0–9 keypad.
- ***No number keys*** — a short Smart Remote.

Each choice is one large tap target with its own drawing. The tap is the step's whole action, so this state has no primary button. After the tap, step 1 shows only that remote's instruction and an illustration of that remote. FR-3's one primary button follows, as before. Both instructions keep step 1's two rules: on a 2024-or-later TV (`appSettingsMenu`) the path goes through **App Settings**, and the instruction says plainly not to type in the search box.
- **Number keys:** go to **Apps** (2024+: *then scroll down to **App Settings***), then press **1 2 3 4 5** on the remote's number keys.
- **No number keys:** go to **Apps** (2024+: likewise), then press the **123** button, which on newer remotes is the button with coloured dots. Number keys appear on the TV. Pick **1 2 3 4 5** there.

A quiet link under the instruction, *I have the other remote*, switches to the other instruction without going back. FR-3's hint line for remotes with number keys (`samsungtv.step1.hint`) is gone, because that case is now a full instruction. The choice is held in the guide's state for as long as the guide is open. A return to step 1, from *Back* or from an FR-4 check that names step 1, shows the chosen remote's instruction directly. Closing the guide forgets it. Nothing is persisted, and nothing reaches the server.

**What it changes elsewhere.**
- **FR-3** — the illustration *remote with 1 2 3 4 5 lit* becomes two drawings, one per remote, still our own vectors in the Ravilo theme: no photograph, no Samsung logo, no model number.
- **FR-8** — new `samsungtv.*` keys in `en`, `da` and `fo`: the question, the two choice labels, the four instruction bodies (two remotes × pre-2024 / 2024+) and the switch link. `samsungtv.step1.body`, `samsungtv.step1.body_new` and `samsungtv.step1.hint` are retired in the same build. They are compiled into the app by `generateRaviloStrings`, so no installed client reads them from the server.
- **FR-9 / FR-12 design gate.** Rannvá draws step 1 first: the question and both instructions, before any Kotlin. The owner picks from her pictures, and the picked direction is written back here as a dated amendment. FR-9's screenshot gate then applies to the build. `GuideScreenshotsTest` gains the question, both instructions on a pre-2024 TV, and the *No number keys* instruction on a 2024+ TV.
- **Verification.** `:ravilo-ui:desktopTest` (`SamsungGuideTest`) covers: step 1 opening on the question; each choice leading to its own instruction; the switch link; and *Back* and an FR-4 *go back to step 1* landing on the chosen instruction, not on the question.

**No wire change.** Phone only. A TV, Tizen, the web and the desktops are untouched.

## Amendment — 2026-11-25 (DEB-5504): the owner picked *Remote tabs* for FR-13

Rannvá drew FR-13 three ways on DEB-5510 (`3b1b9f470`, `design/incoming/2026-11-24-samsung-tv-which-remote/`). The owner picked **C — Remote tabs** on card `c216750d` (2026-11-24). This amendment writes C into FR-13. Where it differs from FR-13 as written on 2026-11-24, this amendment wins. The reference pictures are that round's `png/samsung-tv-which-remote-c-*.png`. Its `swr-data.js` holds the strings, and `samsung-which-remote.css` (direction C, and the shared choice-card and drawing rules) holds the measurements.

**FR-13a · The question state.** Step 1 keeps its title, *Open the hidden menu*, in every state. Under the step indicator and the title, the question *Which remote do you have?* is a prompt line in the body style (16 sp, secondary text colour). Under it are the two choices, side by side: two cards in a two-column grid with a 12 dp gap, each a drawn remote 140 dp tall over its label (*It has number keys* / *No number keys*). Neither drawing is lit before a choice is made. When the two labels don't fit side by side (the 200 % font setting), the cards stack into one column, each a row with a 124 dp drawing at the left and the label beside it. Compose decides this by measuring the label, not by reading the font setting. The question state has no primary button, and it stays top-aligned like every other guide screen.

**FR-13b · The instruction state.** After a tap, the choice turns into a **two-tab control directly under the step indicator**: both labels, the chosen one raised. The tab bar is the full width, with 4 dp padding, 14 dp corners and a 6 % foreground tint behind it. Each tab is at least 44 dp tall, with 13.5 sp semibold text and 10 dp corners. The chosen tab takes the card surface colour, a 1 dp line and a small shadow. It **never** takes the accent colour, because the accent on this screen means "the key to press". Tapping the other tab switches the instruction in place. The tabs **replace** FR-13's *I have the other remote* link, which is not built, and `samsungtv.step1.other` is not added. Under the tabs come the illustration (the shipped 220 × 150 dp slot, unchanged), the title, the chosen remote's instruction body, and FR-3's *Next*.

**FR-13c · The drawings.** Our own vectors (FR-3): no photograph, no Samsung logo, no model number.
- **The two remotes share one scale** — the basic remote 300 units long, the Smart Remote 212 — so the long one looks longer at every size. They are never drawn at the same height.
- The **basic remote** has a 3 × 3 + 0 keypad, a D-pad, and a row of four coloured keys.
- The **Smart Remote** has a round D-pad, a few keys, and one **Number/Color key** drawn larger than true scale. It carries both `123` and four coloured dots, because the instruction names both.
- The four key colours are fixed data, never the accent: `#e05260`, `#3fbf7f`, `#e8c045`, `#4aa3e8`.
- In the instruction state the keys to press are lit, once, with no halo. Keys 1–5 are filled with the accent. The Number/Color key gets an accent ring, so its own marking stays readable.
- The remote's face takes the card surface colour, so it doesn't vanish into the page.

**FR-13d · Copy.** The new `samsungtv.*` keys are `step1.which`, `step1.keys`, `step1.nokeys`, `step1.keys_body`, `step1.keys_body_new`, `step1.nokeys_body` and `step1.nokeys_body_new`, in `en`, `da` and `fo`. The `en`, `da` and `fo` texts are the round's `swr-data.js` drafts. The `*_sub` lines there belong to direction B and are not added. **The spaces inside *1 2 3 4 5* are U+00A0 (no-break) in every language**, so the five digits never break across a line at large font. The `_new` bodies are the 2024+ (`appSettingsMenu`) path. FR-13's retirement of `step1.body`, `step1.body_new` and `step1.hint` stands.

**Settled with the pick** (Rannvá's open rows, decided by Bárður; the owner can overrule them on the screenshot gate):
- the title stays *Open the hidden menu* in the question state (C's own shape);
- the Number/Color key keeps both markings;
- the question state stays top-aligned and is not centred.

**Verification.** FR-13's tests stand, with the switch link replaced by the tabs. `SamsungGuideTest` covers:
- the question state;
- each choice leading to its own instruction;
- **a tab tap switching the instruction without leaving step 1**;
- *Back* and FR-4's *go back to step 1* landing on the chosen instruction with its tab raised.

`GuideScreenshotsTest` gains these states, light and dark:
- the question;
- *It has number keys* on a pre-2024 TV and on a 2024+ TV;
- *No number keys* on a pre-2024 TV and on a 2024+ TV;
- the question in `fo` at the 200 % font setting, with the cards stacked.

FR-9's gate holds: the owner signs those screenshots off on DEB-5504 before the build merges to `main`. **No wire change.**

## Amendment — 2026-11-27 (DEB-5905): a second TV is added from the overview, and every request is on it

The owner, on DEB-5905: *"I tried add an additional Samsung TV to my jeggy user, and the process was a pain, from our ravilo android app … when I finally did everything, the request does not show up on the jellystructure admin part. Let's make this process cleaner. So it's easier to see a list of all my Samsung TVs and all my TV requests."*

**What happened.** Prod holds one request for his account: the first TV, Ready since 2026-11-23. No request for a second TV reached the server, so the admin page had nothing to show. The shipped guide holds only one request. `SamsungGuide.open()` reads the first row that is not Withdrawn, so with one Ready TV the guide opens on *Your TV is approved* and its one button, *Install on TV*. It has no way to add another TV. The second TV can only be reached by a detour. *Install on TV* scans and offers the TVs found. The chosen TV then fails an FR-4 check or FR-7's DUID check, and the failure's button leads back to a scan of every TV and on into steps 1–4. FR-10's overview is specced and drawn (DEB-5413): it shows a *Not set up* TV with *Set up this TV*. It is not built, because the owner's pick of its direction is still open. The server can't tell which branch his attempt took: the backend's log began again at a restart, and the proxy logs only errors. So QA reproduces it.

**FR-14 · The overview is the guide's home for any viewer with a request.** This replaces FR-10's *at least one Approved or Ready request* and FR-6's *Requested and Rejected open as before*. Opening the guide shows *Your Samsung TVs* whenever the viewer has a request that is not Withdrawn. Every such request is on it, as FR-10 items 2–4 draw it. A Requested card also carries FR-6's *Withdraw*, and a Rejected card carries the owner's note and *Ask again*. A viewer with no request still opens on FR-2's intro. `open()` no longer lands on the one-request Waiting, Ready or Rejected screens. FR-6's Approved screen survives only as the Approved card's line, *Comes with the next Ravilo release*.

**FR-15 · Adding a TV starts from its card and ends on the overview.**
- *Set up this TV* on a *Not set up* card runs steps 1–4 (FR-3, FR-13) for that TV. It is the card's own action, not a scan of every TV.
- Once step 4's request is sent, the guide returns to the overview, and that TV's card shows the server's answer. It shows *Waiting for approval* for a Requested row, and FR-10's approved card when FR-7 of `samsung-tv-install-requests.md` answers Ready. Above the list, one line says *Request sent* until the guide is closed. The phone never shows a request as sent unless the server answered `200` or `201` with a row. A refused send stays on step 4 with FR-6's one sentence and *Try again*, and the phone log carries the status.
- A failed FR-4 or FR-7 check started from a card goes back to that TV's own step or to the overview, never to the list of every TV found. A DUID that belongs to another of the viewer's requests goes back to the overview, where each TV's card has its own button.
- A TV the scan doesn't find keeps FR-10 item 4's *Search again*. The guide never asks for an address.

**What it changes elsewhere.**
- **FR-12's screenshot states** gain three: two TVs, one approved and one *Waiting for approval*; *Request sent* over a new *Waiting for approval* card; and a Rejected card with a note. They are covered by `GuideScreenshotsTest`, light and dark. The direction is the owner's pick on DEB-5905. When he has picked, it is written into FR-10 as before.
- **Verification.** `:ravilo-ui:desktopTest` (`SamsungGuideTest`) covers these:
  - `open()` lands on the overview for one Requested row, for one Rejected row, and for one Ready row beside one Requested row;
  - *Set up this TV* leads to step 1 for that TV;
  - a send that succeeds lands on the overview with the new card;
  - a refused send stays on step 4;
  - a DUID mismatch from a card goes back to the overview.
- **QA**, on the row filed from DEB-5905, against the build before this one: on the Pixel 7, with one Ready request and a second Tizen emulator not set up, run the shipped guide to add the second TV and record every screen and the server's rows. Then repeat it on this build.

**No wire change.** The phone reads FR-2's viewer list, which already returns every row.
