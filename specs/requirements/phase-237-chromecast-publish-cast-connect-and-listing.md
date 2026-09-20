# Phase 237 — Chromecast: the whole road to "fully working" — test devices, Cast Connect, and the listing

> 226 named the two fields Google's *Custom Receiver* form asks for, and stopped at "test device or
> publish". The owner then walked the console to the end and found that **publishing is refused**
> until Sender Details, Listing Details (category, countries, title, one-line description) and a 512²
> icon are filled in — none of which the card mentioned — and that a TV which already runs Ravilo
> should open **Ravilo itself** when cast to, not a web page. This phase finishes the card: eight
> steps in three labelled groups, one value or one download for every field Google asks for, and
> the one config-free fact that makes both kinds of TV work.

## Status

`⚠ Partial` — design-authored 2026-09-18, dev-reviewed 2026-09-19 against `main` `dcb97f2c`, **the
admin-facing half built 2026-09-20.** Renumbered from design drafts 228 → 235 → 237.

**FR-237-7's Cast Connect status line, and everything Cast Connect actually does, have moved to
R266** — dev review items 1 and 2, the same carve-out shape 236's review used for FR-236-11. FR-237-7's
two honest *timing* facts (publishing is not instant; your test devices keep working meanwhile) stayed
here and are built, on step 8.

### Build (2026-09-20)

The card is eight steps in three labelled groups, each heading stating its consequence so an admin can
see where it is safe to stop.

- **Group 1 · Register the receiver (1–5).** Steps 1–4 unchanged from 226. **Step 5 is rewritten**
  (review item 3): it now says outright that test devices are **enough for a household**, and gives the
  two device kinds their **different** serials. The old single parenthetical was wrong for this
  household's own TV — for a set with Chromecast built in, the console wants the *Cast software* serial
  under **Settings › Device Preferences › Google Cast**, not the one printed on the back, and it
  changes on a factory reset.
- **Review item 4 taken**: step 3's Package Name qualifier drops the platform (*the Ravilo app*), and
  step 6 says in one clause that it is *the same package as step 3 — the phone and the TV are one app*.
  One value, one story; the previous wording would have had an admin looking for a second package.
- **Group 2 · A TV that runs Ravilo opens Ravilo itself (6).** The Android TV Package Name row, fed
  from the same `BuildInfo.androidApplicationId` as step 3, so one `ravilo.applicationId` edit moves
  both (review item 5 — the spec's `build.gradle.kts:15` reference was stale; phase 226 moved it to
  `gradle.properties`). Plus the note that a **sideloaded** Ravilo still needs that TV as a test device.
- **Group 3 · Publish, optional (7–8).** Every Listing Details field in Google's order with Google's
  labels: Category and Countries as *choose* with no Copy, Title / Description / da / fo with Copy, and
  the Icon with a thumbnail and a **Download**. Countries are deliberately **not** derived from the
  Metadata tab's age-rating regions — `DK · US · GB` is a rating cascade, not a residence.
- **FR-237-5 — the icon is a committed artefact** (review item 6). `cast-receiver/icon-512.png`, beside
  `index.html`, with its code-owned vector master at `cast-receiver/brand/icon-512.svg` carrying the
  regeneration command. No new toolchain, immune to the design sync, and byte-identical across requests
  by construction. Square, not the asset pack's pre-rounded `ic_launcher-512.png` (review item 9 / OQ3:
  reusing that is exactly what puts a hairline of `#000B25` inside Google's own mask).
  **Worth recording: ImageMagick silently dropped the gradient** — its SVG reader ignores
  `fill="url(#…)"` and produced a solid-black mark on navy, which would have shipped an unusable icon.
  Rendered with headless Chromium instead and checked by eye.
- Filename settled as `icon-512.png` everywhere (review item 8).

**Tests:** `tests/e2e/cast-icon.spec.ts` asserts the PNG magic bytes and the IHDR dimensions (an HTML
error page saved as a `.png` is the failure this rules out), that two requests are byte-identical, and
that a missing asset under `/cast/` is an honest 404 rather than `index.html` — which is what lets the
first assertion trust its own 200.

**Not built here:** FR-237-7's *Living room TV opens Ravilo itself* status line, which has nothing to
read until R266 observes a native launch (`CastService.status()` lists `kind == "cast"` devices only,
and a Cast Connect launch produces a `kind == "tv"` one).

Open questions 1, 2 and 4 are all outside this repo and none blocked the card.

## The two kinds of TV — the requirement behind the phase

The household has both: a **Chromecast stick / a TV with Chromecast built in and no Ravilo on it**,
and **a TV that runs the Ravilo Android TV app** (stue). Casting must work on both, and on the second
it must land in Ravilo proper — the same player, skin, subtitle picker and playback tracker the remote
already drives — not in the web receiver, which has none of the household's state.

Google's mechanism is **Cast Connect**: the receiver application is given the Android TV app's package
name; when a phone casts, a TV with that package installed launches it natively, and a TV without it
falls back to the web receiver at `/cast/`. **The TV decides; the phone does nothing differently**
beyond one flag (R266). Both paths share one Application ID, so 218's card and its one field are
unchanged.

The one fact that shapes the card: **Cast Connect only launches an app installed from Google Play,
unless the TV's Cast software serial is registered as a test device in the same console** (Google's
own troubleshooting names the errors: `APP_NOT_INSTALLED_BY_WHITELISTED_INSTALLER`). Ravilo on the
stue TV is a sideloaded APK. So *publishing does not help that TV* — it must be a test device, and the
card says so where the admin would otherwise expect publishing to be the fix.

## Functional requirements

**FR-237-1 — Eight steps in three labelled groups.** Each group header states its consequence, so
an admin who wants only the first can see where to stop:

- **Register the receiver** *(works on any Chromecast, with or without Ravilo)* — steps 1–5.
- **A TV that runs Ravilo opens Ravilo itself** *(Google calls this Cast Connect)* — step 6.
- **Publish** *(optional — lets any Chromecast use it, not only the ones you listed)* — steps 7–8.

Steps 1, 2 and 4 are 226's unchanged. Step 3 keeps 226's two rows and adds one clause: the Package
Name goes under **Sender Details → Android**, and **Intent to Join URI** and **Website URL** may stay
empty — with the reason in one clause on the card: Intent to Join is for joining a cast *another* app or
a voice command started, and Ravilo finds a running cast by itself (R245 re-connect; R266 non-goals).
Google needs *at least one sender* to publish, and the Android sender is that one.

**FR-237-2 — Step 5 is test devices, and it is where a household stops.** The console asks for a
serial per device. A Chromecast's is in the Google Home app. A TV with Chromecast built in has **two**:
the card names the **software serial** under *Settings › Device Preferences › Google Cast*, says it is
not the one printed on the TV, and that it changes after a factory reset. The step ends with the
sentence *This is enough for a household — casting works on every device you list, and nothing below
is required.* This is the honest answer to "do I have to publish?": no.

**FR-237-3 — Step 6 is the Android TV package name, plus the sideload rule.** One field row in the
226 idiom — label as the console shows it, qualifier *the Ravilo app on the TV · the same build as the
phone's*, value `dev.jellystructure.ravilo` (the phone and TV are one `applicationId`;
`ravilo-android/build.gradle.kts:15`), Copy. Beneath it, one note: *If Ravilo on that TV was installed
from an APK rather than Google Play, the TV must be one of your test devices from step 5 — publishing
does not change that. Without it, the TV opens the receiver instead.* Nothing in config changes; the
same Application ID serves both paths.

**FR-237-4 — Step 7 is every Listing Details field, verbatim, with its answer.** One bordered group,
one row per field Google asks for, in Google's order and with Google's labels:

| Console field | On the card | Kind |
|---|---|---|
| **Category** | `TV & Movies` | *choose from the list* — no Copy |
| **Countries** | *where your household is* · *Only the listing is limited to these — casting works everywhere.* | *choose* — no Copy |
| **Title** | `Ravilo` | Copy |
| **Description** | `Your family's own film and series library, cast to the TV.` | Copy |
| **Icon** *(512 × 512 · PNG)* | thumbnail of the mark on `#000B25` · `ravilo-icon-512.png` | **Download** |
| **Additional Translations** *(optional)* | the Description in **da** and **fo** | Copy each |

The step's own sentence carries the two things the admin must know before filling any of it: *Google
will not publish without a listing and at least one sender (step 3),* and *The listing describes
Ravilo, not your library — nothing about what you have reaches Google.* Countries are **not** derived
from anything in Settings — the Metadata tab's age-rating region order (`DK · US · GB`) is a rating
cascade, not a residence, and a guessed country list that is wrong is worse than a *choose*.

**FR-237-5 — The icon is served, not found.** jellystructure serves `GET /cast/icon-512.png`: a
512 × 512 opaque PNG, the Ravilo mark (`assets/brand/ravilo-mark.svg`, the same file the receiver's
idle screen shows) at 60 % on the `#000B25` brand ground, square corners (Google applies its own
mask). It is one more static file in 218's `/cast/` bundle, generated at build time from the vector
master — never rendered per request. The card's **Download** is a plain link to that path with a
`download` attribute; it works whether or not the public address is set, because it is fetched from
the admin's own browser session, not from outside.

**FR-237-6 — The description strings are product copy, not viewer strings.** English is canonical.
Danish *Familiens egen film- og seriesamling, castet til tv'et.* and Faroese *Familjunnar egna filma-
og røðsavn, sent á sjónvarpið.* are **drafts** for the owner to correct; they live in the admin
frontend's own string table (they are shown to the admin, copied to Google, never rendered on a TV),
so they are **not** `ravilo-i18n.js` keys and R266 adds none.

**FR-237-7 — Step 8 is Publish, with the two honest timing facts.** Not instant; and *your own test
devices keep working meanwhile*. The registered state's status list gains one line when a Cast Connect
launch has been observed: *Living room TV opens Ravilo itself when cast to · other devices get the
receiver* — observed from the receiver-side enrolment (R266 FR-R266-6 distinguishes the two), never
asserted from the console.

**FR-237-8 — Nothing else changes.** No config key, no new state on the card, no viewer-facing string.
218 FR-218-7's rule stands: only a real cast confirms an Application ID, and only a real cast confirms
that a TV opens Ravilo rather than the receiver.

## Non-goals

- **Automating anything at Google.** There is no API for registration, listing or publishing.
- **Cast Connect for the phone-to-phone or Wasm case.** Cast Connect is an Android TV launch; the web
  receiver remains the only receiver for every other target.
- **Screenshots of the console** (226's reason: they rot; labels do not).
- **Localising the listing beyond da/fo.** Google machine-translates the rest; the household has three
  languages.
- **A "Play on Stue TV" (phase 111) affordance.** That is jellystructure's own, Google-free hand-off
  to a TV running Ravilo and is drawn in the mobile round 2 beside the cast button. It is
  complementary, not a substitute: Cast Connect is what makes *the Cast button itself* land in Ravilo.

## Acceptance

1. `#cc-steps` shows three group headers and steps numbered 1–8 continuously; step 5 ends with the
   *enough for a household* sentence; the `off` state renders none of it (218 FR-218-4).
2. Step 3 has exactly the two 226 rows; step 6 has one row whose value equals the sender build's
   `applicationId`; changing `applicationId` changes both without a config edit.
3. Step 7 has six rows in the order above; Category and Countries have no Copy; Title, Description and
   both translations copy their value verbatim; Icon's Download fetches `/cast/icon-512.png`.
4. `GET /cast/icon-512.png` returns `image/png`, 512 × 512, no alpha, background `#000B25`, and is
   byte-identical across requests (a build artefact, not a render).
5. With `public_url` unset, step 3's URL row shows *Set your public address above first* (227) and the
   icon Download **still works**.
6. The registered state shows the *opens Ravilo itself* line only when R266's enrolment has recorded
   at least one native launch; never on the strength of the console's settings.
7. No string added by this phase names any product except Google.

## Source references

- `design/app/settings.html` — `#cc-steps`, `.cc-grp`, `.cc-note`, `.cc-fvv.pick`, `.cc-icon`,
  `#cc-icon-dl` (the mockup draws the PNG on a canvas from the SVG; the server ships a file).
- `ravilo/assets/brand/ravilo-mark.svg` — the icon's vector source; `design/ravilo/Ravilo - Android
  TV Assets.html` — the same mark, ground `#000B25`, and an existing 512² `ic_launcher-512.png` under
  `assets/store/` that FR-237-5's artefact may simply *be*.
- `ravilo-android/build.gradle.kts:15` — `applicationId`, both package-name rows.
- Phases **218** (the receiver, the card, the honest-verification rule), **226** (the two fields),
  **227** (the address the URL derives from), **R245** (the sender), **R266** (Cast Connect on both ends).
- Google: *Cast Connect — Cast Developer Console setup* and *Android TV receiver troubleshooting*
  (`APP_NOT_INSTALLED_BY_WHITELISTED_INSTALLER`, software vs hardware serial); *Registration* (the $5
  fee is non-refundable; test devices; publishing "makes it available to all Cast devices").

## Open questions — for the dev team

1. **The console's label for the Android TV package field.** Design writes *Android TV package name*
   from Google's documentation, not from the live form (the owner's screenshot stopped at Listing
   Details). Re-read the form once and correct the label in one place, as 226 OQ1.
2. **Is the stue BRAVIA's Cast software serial stable enough to register once?** Google says it
   changes on factory reset only. If the TV is ever reset, step 5 must be repeated — worth a line in
   the household runbook rather than on the card.
3. **Should `/cast/icon-512.png` be the existing `assets/store/ic_launcher-512.png`** (rounded
   corners baked in, from the Android asset pack) or a fresh square render? Google masks listing icons
   itself; a pre-rounded icon inside Google's mask shows a hairline of ground. Design leans **square**.
4. **Does publishing change anything for the sideloaded TV?** Design's reading of Google's docs is
   *no* — the whitelisted-installer check is independent of publish state. If dev finds otherwise, the
   FR-237-3 note softens; it does not disappear.

## Dev review (2026-09-19, against `main` `dcb97f2c`)

Traced line by line against the shipped card (`src/wasmJsMain/kotlin/dev/jellystructure/ui/Settings.kt`),
the receiver route (`Server.kt`), `CastService.kt`, `Main.kt`, `Dockerfile`, `build.gradle.kts` and
`gradle.properties`. The card's five steps, the two field rows and the derived address are as described.
**The listing half (steps 7–8, the icon) is sound and can be built as written, with one change to how the
icon is produced. The Cast Connect half rests on a mechanism that does not exist and would, if built on
today's route, produce the wrong device.**

1. **The enrolment FR-237-7 reports on cannot happen, and today's route would mint a second device for
   the same TV.** `CastService.redeem` (`:102`) rejects any `receiverId` that does not start `cast-`
   (`:109`), forces the display name to `"Chromecast via Ravilo · …"` (`:110`) and passes `kind = "cast"`
   (`:126`). A Cast Connect launch lands in the **Android TV Ravilo app**, which is already an enrolled
   device holding its own token and its own `kind`. Redeeming a hand-off code from there would create a
   *second* `ravilo_device` row for one physical TV, name it a Chromecast, and put it under 218's ceiling —
   `checkCeiling` (`:134`) gates on exactly `kind == "cast"`, and 236 FR-236-9 says a TV must never count
   there. **Recommendation: R266 needs no redemption at all.** 236 shipped the mechanism already — the TV
   is an enrolled device, so the play arrives as `play_item` over `TvEventBus`. R266 shrinks to three
   things: `androidReceiverCompatible` on the sender, the launch intent, and `CastReceiverContext`
   translating Google's load request into 236's existing play against the token the app already holds.
   (If a redemption is ever genuinely wanted, `redeem` needs a `kind` + id-prefix parameter *and*
   `checkCeiling` must keep ignoring the result — but a device that already holds a token should not be
   redeeming a code to get one.)
2. **FR-237-7's status line has nothing to read, and should leave this phase.** `CastService.status()`
   (`:174`) builds its device list from `allDevices().filter { isCastDevice }` — `kind == "cast"` only —
   and `renderChromecastStatus` (`Settings.kt:3164-3172`) renders from that list. A Cast Connect launch
   produces a `kind == "tv"` device that never enters it, so *Living room TV opens Ravilo itself* cannot be
   sourced from `ChromecastStatus` as it stands; it needs its own field (a `lastNativeLaunchAt`) set where
   the launch is observed, and per item 1 there is no such place yet. **FR-237-7's status line is carved
   out of this phase and lands with R266**, the same way 236's dev review carved out FR-236-11. Steps 1–8
   do not depend on it, and the card should not wait on a Ravilo phase. FR-237-7's *two honest timing
   facts* (publishing is not instant; your test devices keep working meanwhile) stay here.
3. **Step 5's shipped parenthetical is wrong for this household's own TV — fix it, don't just split it.**
   `Settings.kt:211` reads *"Add your Chromecast as a **test device** (its serial number is on the device
   and in the Google Home app), or **publish**…"*. For a TV with Chromecast built in that is the printed
   hardware serial, which is **not** the one the console wants; FR-237-2 is right that it is the software
   serial under *Settings › Device Preferences › Google Cast*. When the step splits, the old parenthetical
   must not travel with it — the Chromecast stick keeps the Google Home hint, the built-in TV gets
   FR-237-2's wording, and both live in step 5.
4. **The Package Name row already exists, and FR-237-3 makes it appear twice with two explanations.**
   Shipped step 3 (`Settings.kt:206`) renders it under Google's label **Package Name**, qualified *the
   Ravilo app on your phone*; FR-237-3's step 6 renders **the same string** qualified *the Ravilo app on
   the TV*. One value, two rows, two stories — an admin will reasonably conclude they are different
   packages and go looking for a second one. **Step 3's qualifier drops the platform** (*the Ravilo app*),
   and step 6's row says in one clause that it is *the same package as step 3 — the phone and the TV are
   one app*.
5. **Acceptance 2 already holds by construction; the source reference is wrong.** `applicationId` is not a
   literal at `ravilo-android/build.gradle.kts:15`. Phase 226 moved it to `gradle.properties:19`
   (`ravilo.applicationId=dev.jellystructure.ravilo`), read by `ravilo-android/build.gradle.kts:16` and by
   the root `build.gradle.kts:437-451`, which generates `BuildInfo.androidApplicationId`; the card reads
   that constant (`Settings.kt:3287`). Both rows therefore move together with one property edit, and
   nothing new is needed for step 6's value. Correct the reference.
6. **The icon must not be generated at build time from the design tree.** Two independent reasons.
   (a) **Nothing in the build can rasterize an SVG** — no `rsvg`, `inkscape`, `resvg` or `cairosvg` in
   `Dockerfile`, `build.gradle.kts` or `scripts/`; FR-237-5 as written adds a toolchain dependency to the
   builder image for one 512² PNG that changes when the brand changes, i.e. almost never. (b) The vector
   master exists **only** at `design/ravilo/assets/brand/ravilo-mark.svg` — under `design/`, which the
   "updated designs" sync overwrites wholesale (STATUS.md's own standing warning; the same class of
   incident as `specs/research-reports/`). A build step that reads from `design/` is a build the next
   design export can break silently, at image-build time, in CI. **Recommendation: commit the artefact.**
   `cast-receiver/` is a plain static directory whose `index.html` is committed and whose `ravilo-cast.js`
   is gitignored and produced by `:ravilo-cast:syncCastReceiver` (`Dockerfile:39-40, 63-67, 101`). Add
   `cast-receiver/icon-512.png` as a checked-in file beside `index.html`, with the SVG it was rendered
   from copied to a **code-owned** path and a one-line regeneration note next to it. Zero new toolchain,
   acceptance 4 ("byte-identical across requests") true by construction, and immune to the sync. FR-237-5's
   *never rendered per request* survives unchanged; only *generated at build time from the vector master*
   goes.
7. **The route already behaves the way acceptance 4 and 5 need — no backend work beyond the file.**
   `/cast/{...}` (`Server.kt:669-673`) is a plain static route outside the API auth plugin, so the
   Download carries no credential and `public_url` is not involved (acceptance 5 holds as written).
   `serveFrontendFile` returns a real 404 for an asset-shaped path that does not exist rather than falling
   back to `index.html` (FR-235-3, `Server.kt:762-766`) — so a missing icon is an honest 404, not an HTML
   page saved as a `.png`. `contentTypeFor` already maps `png → image/png` (`:822`). ETag plus `no-cache`
   revalidation apply (`:779-799`) and the name carries no content hash, so it is never cached immutably —
   correct for a file that may be re-rendered. One caveat worth a line on the card: the route exists only
   `if (castDir != null)` (`Server.kt:669`, `Main.kt:88` — `CAST_DIR` or `cast-receiver/`, and only if the
   directory is present), so the Download is the single step that fails as a 404 rather than as a message.
   Committing the PNG (item 6) also makes the directory non-empty in every checkout.
8. **The filename disagrees with itself.** FR-237-4's table shows the file to the admin as
   `ravilo-icon-512.png`; FR-237-5 and acceptance 3/4 serve it at `/cast/icon-512.png`. Pick one — the
   served path is the one that has to be typed nowhere, so the table's label becomes `icon-512.png`.
9. **Open questions.** **OQ3 — square, and for a sharper reason than the lean gives.**
   `design/ravilo/assets/store/ic_launcher-512.png` is the Android asset pack's *pre-rounded* file;
   reusing it is exactly what puts a hairline of `#000B25` inside Google's own mask. Render square from
   the mark. **OQ1** (the console's label for the Android TV field), **OQ2** (serial stability across a
   factory reset) and **OQ4** (whether publishing affects the whitelisted-installer check) are all outside
   this repo and none of them blocks the card — FR-237-3's note is the safe reading either way, and OQ1 is
   a one-place string correction after one look at the live form, as 226 OQ1 already established.

**Build order.** Steps 1–5 corrections (items 3, 4) and steps 7–8 with the committed icon (items 6, 7, 8)
are buildable now and are the whole admin-facing value of this phase. Step 6's row is buildable now too
(item 5). **FR-237-7's status line and everything Cast Connect actually does move to R266** (items 1, 2),
which itself shrinks to a sender flag, a launch intent and a translation into 236's `play_item`.
