# Phase 237 — Chromecast: the whole road to "fully working" — test devices, Cast Connect, and the listing

> 226 named the two fields Google's *Custom Receiver* form asks for, and stopped at "test device or
> publish". The owner then walked the console to the end and found that **publishing is refused**
> until Sender Details, Listing Details (category, countries, title, one-line description) and a 512²
> icon are filled in — none of which the card mentioned — and that a TV which already runs Ravilo
> should open **Ravilo itself** when cast to, not a web page. This phase finishes the card: eight
> steps in three labelled groups, one value or one download for every field Google asks for, and
> the one config-free fact that makes both kinds of TV work.

## Status

`Planned` — written 2026-09-18, **not dev-reviewed**. Supersedes **FR-226-1** and **FR-226-3** (the
step list); FR-226-2's two field rows stand verbatim inside step 3. Pairs with **R266** (the TV app as
a Cast Connect receiver, and the phone sender flag) and adds one static file to 218's `/cast/` bundle.

**Numbering:** originally written and numbered 228 on 2026-09-18, renumbered **228 → 235** the same day
(`main` had taken 228 for an unrelated backend memory-leak phase), and renumbered again **235 → 237**
hours later when `main` took **235** (serve the web app like an app) and **236** (screens: the backend
drives a TV for a phone) before this draft was ever pushed. Same shape as R196→R208, 179→180, 186→187:
the dev tracker's number wins, ours moves — twice in one day. Verified against `main` on 2026-09-18 —
admin taken through **236**, Ravilo through **R265**. Next free: 238 / R269.

Design: built into `design/app/settings.html` (Connections → Chromecast, `#cc-steps`; new page-local
classes `.cc-grp`, `.cc-note`, `.cc-fvv.pick`, `.cc-icon`; the Download button really produces the
512² PNG in the mockup, the way the server will). The card's base five steps (1–5) now match the
canonical, dev-reviewed **226**/**227** build pulled from `main` on 2026-09-18; this phase's three
groups/eight-step structure and steps 6–8 are layered on top of that base, unbuilt.

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
