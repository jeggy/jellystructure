# Ravilo can be the TV's home screen

Task: DEB-4909

> Owner, 2026-11-17 (DEB-4906): he wants to boot straight into Ravilo, with small extensions such as opening other
> apps like DR TV and opening Android settings, *"and maybe something else we also need to consider"*. Switching between
> Ravilo and the stock home must be easy, and no agent may do it without his permission: *"is there some config for it
> in the android settings, so no agent will do it for us, unless we give permission"*. Then: *"also fine to have this
> setting in ravilo itself, so we can enable/disable this launcher directly from ravilo itself and then it would hook
> itself into the tv os launcher or back to the standard launcher"*. Then: *"we will only test this whole thing on the
> 'Google TV Streamer (via Kambi Mac)'"*.

The investigation is DEB-4906's report. This spec is that report after review (DEB-4909). Where the spec and the report
disagree, the spec wins.

## What a TV does today

- Ravilo's TV entry is `.android.MainActivity` (`singleTask`). Its only filters are `LEANBACK_LAUNCHER` and Cast
  Connect's `LAUNCH`/`LOAD` (`ravilo-android/src/main/AndroidManifest.xml`). The APK is universal: the same one goes to
  phones through Play's closed testing track (R224, R215).
- Home on the remote always opens the stock Google TV home (`com.google.android.apps.tv.launcherx` on the Streamer and
  the Sony TVs). Google TV has no "Home app" choice in its Settings.
- Back at the top of Ravilo's Home leaves the app (`RaviloApp.kt`, `atHomeRoot -> exitApp()`; R55's scroll-to-top
  comes first).
- Ravilo cannot open another app, Android's settings or the stock home. A signed-out TV shows only sign-in. A TV that
  cannot reach the server shows only Ravilo's error and Retry.

## How it works: the hook

The stock home stays installed and stays the system's home. Ravilo adds a small Android **accessibility service**, the
*hook*. When it is on, it notices the stock home's own screen coming forward and brings Ravilo up instead. This covers
a Home press from anywhere, the boot and the wake from standby. Projectivy and Monet work this way on Google TV.

Android never lets an app turn its own accessibility service on. So there are two switches:

1. **Android's switch, once per TV:** *Settings → System → Accessibility → Ravilo*. A person with the remote turns it
   on and confirms Android's dialog. This is the owner's permission gate.
2. **Ravilo's switch:** *Use Ravilo as home screen*, in Ravilo's Settings on that TV. Once Android's switch is on,
   this one turns the hook on and off at once, with no restart.

**Changes from the DEB-4906 draft, and why:**

- **No HOME `activity-alias` in this spec.** With the hook, Android's Home intent still resolves to the stock home.
  A HOME entry in Ravilo would only matter for the ADB fallback (disabling `launcherx`). Turning one on can also make
  Android ask which home to use, and keeping it out removes all risk that a phone offers Ravilo as its home app. If
  the hook fails on the Streamer, the fallback comes back as an amendment here.
- **The crash guard steps aside instead of showing a "safe mode".** The stock home is never disabled. So if Ravilo
  keeps closing, the hook stops and the Google TV home is what's left (FR-8). Nothing new has to be drawn or kept
  working offline for this case.
- **No pinned apps in the first version.** Apps is one A–Z list of the TV's own apps (FR-11). That list is a fact
  about the TV, not a viewer setting, so the question of where pins are stored doesn't come up yet.
- **No "kids profile" app hiding.** jellystructure has no kids concept (owner, 2026-10-17). The Apps list is the same
  for every profile on the TV.
- **Sony is not a test target** (owner, 2026-11-17). The Streamer is. Nothing hides the setting on other Google TVs.

## Requirements

### The switch

**FR-1 · A TV-only setting.** Ravilo's Settings on an Android TV gains a section **Home screen** with one switch,
*Use Ravilo as home screen*, off by default. The section is not shown on the phone, web, Mac, Linux, Chromecast or
Samsung clients. The value is stored on the TV only. It is not per viewer, it is not synced through jellystructure and
it is not part of `RaviloConfig`. Every profile signed in on that TV sees and changes the same switch.

**FR-2 · The one-time Android step.** When the viewer turns the switch on while Android's switch for Ravilo is off:

- Ravilo first shows one screen saying, in plain words, what the hook does: it only notices when the TV's own home
  screen appears, so that Ravilo can open instead. It reads nothing on screen and nothing the viewer types. This
  screen comes before Android's own warning and is the explanation Play requires.
- That screen's button opens Android's accessibility settings, on Ravilo's own page where the TV supports it.
- When the viewer comes back, the setting shows the true state of Android's switch.

Ravilo's setting never says *On* while Android's switch is off. It says that one step in Android settings is still
needed, with the same button.

**FR-3 · On.** With both switches on, and within two seconds:

- **Home from anywhere** opens Ravilo: from another app, from Android's settings, and from the stock home itself.
- **After a boot**, and **after waking from standby**, the TV ends up in Ravilo.
- **Home inside Ravilo** lands on the top of Ravilo's Home, focus on its first target. It closes a player that is
  open, and any overlay, as Back would.
- **Back at the top of Home does nothing.** It no longer leaves the app, because leaving would only bring the hook
  back.

A short flash of the stock home before Ravilo comes up is accepted.

**FR-4 · Off.** Turning Ravilo's switch off returns Home, boot and wake to the stock Google TV home straight away, with
no restart. Back at the top of Home leaves the app again. Android's switch stays on, so turning Ravilo's switch on
again needs no trip to Android's settings. Turning Android's switch off has the same effect, and Ravilo's setting
then shows FR-2's one-step state.

**FR-5 · The stock home is one press away.** While the hook is on, Ravilo offers an entry that opens the stock home.
It is labelled with the stock home's own name as Android reports it (on the Streamer, Google TV's). Opening it is not
undone by the hook. The stock home stays until the viewer opens something else from it. The next time the stock home
comes forward after that, Ravilo takes it again. In the first build slice the entry sits in the Home screen section
of Settings. FR-12 adds it to Apps.

### What the hook may and may not do

**FR-6 · Only the home screen itself.** The hook acts only when the stock home's own home screen comes forward. It
leaves alone the stock home's search and voice results, its profile and quick-settings panels, the input picker,
Android's settings, the screensaver, Cast, and anything the Assistant opens. The stock home is found at runtime as
whatever Android resolves the Home intent to, never by a hard-coded package name.

**FR-7 · The hook is narrow and changes nothing else.**

- Ravilo never turns its own accessibility switch on and never asks for `WRITE_SECURE_SETTINGS`.
- It never disables, hides or changes another app, the stock home included.
- No Ravilo component declares the `HOME` category.
- The service listens to window changes of the stock home only. It does not read window content
  (`canRetrieveWindowContent` off), does not take gestures and keeps nothing.
- On a phone the service is never listed in Android's accessibility settings. Ravilo keeps the component disabled
  until FR-2 runs on a TV.

**FR-8 · It steps aside if Ravilo keeps closing.** If Ravilo closes unexpectedly twice within five minutes of being
brought up by the hook, the hook stops acting, and the stock home stays. Ravilo's setting then shows that it paused
itself because Ravilo closed unexpectedly. The hook comes back only when the viewer turns the switch off and on again.
A TV must never be left in a loop where every Home press opens an app that closes.

**FR-9 · Words.** Every new string is in `i18n/en.json`, `da.json` and `fo.json`, including the service description
Android shows on its accessibility page. No string names Jellyfin (Ravilo constitution, invariant 12).

### Apps, Android settings and the stock home from Ravilo

FR-10 to FR-13 have a surface. The design round on this task draws them: where the Apps entry sits in the TV header,
the Apps screen, and the exits on the sign-in and can't-reach-the-server screens.

**FR-10 · An Apps entry.** While the hook is on (FR-3), Ravilo's TV header has an **Apps** entry. With the hook off,
the entry is not there.

**FR-11 · Apps.** Apps shows, in this order:

1. **Android settings.** It opens `Settings.ACTION_SETTINGS`.
2. **The stock home** (FR-5).
3. **Every app on the TV that has a TV launcher entry** (`MAIN` + `LEANBACK_LAUNCHER`), A–Z by its label, each with
   its TV banner, or its icon and label where it has none. Ravilo itself is left out. Selecting one opens it with its
   leanback launch intent.

The list is read from the TV each time Apps opens, so an app installed or removed shows up without a restart. It works
with no network and with nobody signed in: nothing in it comes from jellystructure. The manifest gains the `<queries>`
entry Android 11+ needs to see those apps.

**FR-12 · No dead end while the hook is on.** On every screen Ravilo can show in place of Home (sign-in, profile
picker, server setup, and the screen that says Ravilo can't reach the server), Apps is reachable in one press. So
Android's Wi-Fi settings are always reachable, and so is the stock home.

**FR-13 · Shared UI.** Apps is a `:ravilo-ui` screen. Only reading the app list and launching an app, Android settings
or the stock home are Android `actual`s. Other platforms get no-op `actual`s and never show the entry.

## Out of scope

- **Disabling the stock home over ADB** (the DEB-4906 fallback), and any HOME entry in Ravilo. These come back only as
  an amendment if FR-3 fails on the Streamer.
- **Pinning or reordering apps**, and showing other apps' "continue watching". The second needs a system permission
  Ravilo can't have.
- **Switching HDMI inputs** from Ravilo. The remote's Input button keeps working.
- **Notifications and Google TV's quick-settings panel.** They stay on the stock home, one press away (FR-5).
- **Hiding apps per viewer.**

## Verification

Automated, in CI:

- `:ravilo-ui:desktopTest`: FR-1's section shows only where the platform says it is a TV. The FR-2 states: Android's
  switch off shows the one-step state and never *On*. FR-3's Home-inside-Ravilo goes to the top of Home, and Back at
  the top of Home does nothing with the hook on and exits with it off. FR-10's entry exists only with the hook on.
  FR-11's ordering is: settings, stock home, then apps A–Z with Ravilo left out. FR-12: Apps can be reached from
  sign-in and from the can't-reach screen.
- Also `:ravilo-ui:desktopTest`, as plain Kotlin in common code: FR-6's decision of what counts as the stock home's
  home screen, given a sequence of window changes, including FR-5's pass-through. FR-8's
  two-closes-in-five-minutes guard, with an injected clock.
- `:ravilo-ui:testDebugUnitTest` (Robolectric): FR-7. No merged-manifest component carries `HOME`, and the service
  is disabled by default with `canRetrieveWindowContent` false.

On a device, because a real accessibility service, a real Home key and a real boot can't run in CI: QA on the
**Google TV Streamer (via Kambi Mac)** only. Its test plan covers FR-2 to FR-8 and FR-11 to FR-12. QA turns Android's
accessibility switch for Ravilo on and off on the Streamer only with the owner's OK on the QA task, and leaves both
switches off at the end. The Sony TVs are not tested.
