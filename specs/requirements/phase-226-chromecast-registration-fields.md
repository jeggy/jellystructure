# Phase 226 — Chromecast registration: name the fields, give the values

> Phase 218 FR-218-6 put the Google registration inline as three numbered steps, which was right, and
> then described them in summary — *"choose Custom Receiver and paste the address above"*. The admin
> doing this is looking at **Google's form**, not at ours, and that form asks for two things by name.
> This phase replaces the three steps with five, and names every field the console asks for, the exact
> value to type into it, and a Copy beside each. No new config, no new route, no behaviour change.

## Status

`Planned` — written 2026-09-17, **not dev-reviewed**. Supersedes **FR-218-6** only; every other 218
requirement stands unchanged.

**Numbering:** verified against `main` on 2026-09-17 — admin taken through **225** (ours, same day).
Next free: 227.

Design: built into `design/app/settings.html` (Connections → Chromecast, the `#cc-steps` box and the
page-local `.cc-fv*` classes). **Pairs with 227**, which owns the address the URL is built from.

## Current state

- FR-218-6's three steps are in the shipped card: *Register an application … (US$5)* · *Choose Custom
  Receiver and paste the address above* · *Add your Chromecast as a test device, or publish it.*
- The console's URL is not on the card at all — the admin is told the console's name and left to find it.
- The **Package Name** field is not mentioned anywhere in 218 or R245, and the Cast console's Custom
  Receiver form asks for it. Without it the phone's own build is not associated with the receiver, which
  is exactly the class of failure FR-218-7 can only surface as "cast once and see".
- The Application ID hint says *"after step 1"*, which was already wrong — the ID appears on save.
- The sender's package name exists in the repo as `ravilo-android/build.gradle.kts:15`
  (`applicationId = "dev.jellystructure.ravilo"`, debug builds `+ ".debug"`), so the value is knowable
  and must not be left to the admin to guess.

## Functional requirements

**FR-226-1 — Five steps, in this order.** (1) Open the console, with the URL as a real link; the
one-time US$5 fee named here. (2) *Add New Application* → *Custom Receiver*. (3) The two fields (FR-226-2).
(4) Save; the console shows the **Application ID**; copy it into the card's field. (5) Test device *or*
publish, with the serial-number hint and the honest note that publishing is not instant.

**FR-226-2 — The two fields, verbatim and copyable.** One row per field inside a single bordered group:
the console's **exact on-screen label** (the 212 rule: our words are useless if they don't match the form
the admin is reading), a short qualifier after it, and the value on its own line in monospace with a Copy.
Nothing more — the long-form caveats belong here in the spec, not on the card:

| Console field | Qualifier on the card | Value |
|---|---|---|
| **Receiver Application URL** | *your public address + /cast/* | the installation's `public_url` (**227**) + `/cast/` |
| **Package Name** | *the Ravilo app on your phone* | `dev.jellystructure.ravilo` |

Caveats deliberately **not** on the card: that https is required (the address field above already says the
receiver must be reachable over https); that the console does not validate the package name; and that a
debug build needs `dev.jellystructure.ravilo.debug` (true, but it is a developer's problem, not an
admin's).

The URL value is derived in one place from the installation's single **Public address** field (**227**
FR-227-4) and rendered exactly once on the page — 227 deletes the Chromecast card's own address field, so
there is no second copy to disagree with. With no public address set, the row shows *Set your public
address above first* and no Copy (227 FR-227-6). The package name is a **build constant**, read from the
sender's `applicationId`, not typed into config by anyone.

**FR-226-3 — Say which fields don't matter, in one clause.** The step's own line reads *Fill in two
fields. Name it anything; everything else can stay as it is.* An admin who does not know which of a dozen
console fields are load-bearing will fill them all in, wrongly — and a paragraph explaining that is worse
than a clause.

**FR-226-4 — The Application ID hint is corrected** to *"From the console, after step 4."* and the
registered state's link reads *Show the registration steps again*.

**FR-226-5 — Nothing else changes.** No config key, no route, no validation, no new state. FR-218-7's
honest verification is untouched: only a real cast confirms an Application ID, and the status list still
says so. Google remains the only product named on this card and nowhere else (FR-218-6's rule).

## Non-goals

- **Automating the registration.** There is no API; the admin does this by hand at Google, once.
- **Validating the package name or URL against Google.** Same reason as FR-218-7.
- **Screenshots of Google's console.** They rot every time Google redraws it; verbatim field labels do
  not, and a wrong screenshot is worse than none.
- **A shared project-hosted receiver** (218's non-goals, owner decision).

## Acceptance

1. The card's step list shows five steps; step 1 links to `https://cast.google.com/publish/` and opens
   in a new tab with `rel="noopener"`.
2. Step 3 shows exactly two field rows, labelled **Receiver Application URL** and **Package Name**, each
   with a Copy that puts the value on the clipboard verbatim (no trailing whitespace, no added scheme), and
   **no more than one short qualifier each**.
3. The URL block's value is byte-identical to the *Your receiver address* field above it in every state,
   including when `public_url` is changed and the page re-renders.
4. The package name block's value equals the sender build's `applicationId`; changing the sender's
   `applicationId` changes the card without a config edit.
5. The Application ID hint reads *"From the console, after step 4."*; the registered state's link reads
   *"Show the registration steps again"*.
6. In the `off` state nothing above is rendered at all (218 FR-218-4 unchanged).
7. No string added by this phase names any product except Google; no viewer-facing string changes.

## Source references

- `design/app/settings.html` — `#cc-steps` (the five steps, the two field rows) and `.cc-fv` /
  `.cc-fvk` / `.cc-fvv` in the page-local style block. `cc-` prefix, deliberately not
  `adv-*` (2026-09-15: an ad blocker's cosmetic filter hides that class outright).
- `ravilo-android/build.gradle.kts:15` — `applicationId`, the source of the Package Name value.
- `specs/requirements/phase-218-chromecast-receiver-and-registration.md` — FR-218-4/5/6/7 (this phase
  replaces 6 only); `specs/ravilo/requirements/phase-R245-cast-sender-receiver-and-remote.md` — the
  sender that the package name associates.
- Phase **212** — the rule this phase applies: an advisory surface must quote the *other* product's exact
  on-screen label, not our API's name for the same thing.

## Open questions — for the dev team

1. **Does the console's Custom Receiver form still label these two fields exactly this way?** Design has
   the labels from the admin's own form (2026-09-17). Google redraws this console occasionally; whoever
   builds this should re-read the live form once and correct both labels in one place if they differ.
   Everything else in the card is independent of Google's wording.
2. **Is `Package Name` required for a plain sender, or only for Cast Connect / Guest Mode?** If the
   console accepts an empty value for our case, keep the field on the card anyway (it costs one block and
   prevents a silent association failure) but soften its note from *must* to *should*.
3. **Where should the package name live for the Wasm/web sender?** It has no Android package. If a web
   sender is ever registered, the block should hide rather than show an inapplicable value — flag it when
   R245's Wasm half is picked up.
