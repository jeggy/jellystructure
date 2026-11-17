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
- **Pins are kept on the TV, not in jellystructure** (owner's answer below). The list of installed apps is a fact
  about the TV, and the owner chose to keep the pins with it.
- **No "kids profile" app hiding.** jellystructure has no kids concept (owner, 2026-10-17). The Apps list is the same
  for every profile on the TV.
- **Sony is not a test target** (owner, 2026-11-17). The Streamer is. Nothing hides the setting on other Google TVs.

## Owner's answers (2026-11-17, DEB-4909 card `eb3ce3bd`)

- **The constitution amendment:** yes. It is in `specs/ravilo/constitution.md`, under Vision.
- **Apps:** *"Pinning now, stored on each TV. But with an additional see all apps button, which then shows all the
  apps in A-Z or when last used (if not too much work)"*. See FR-11, FR-14 and FR-15. "When last used" is Ravilo's
  own record of what it opened. Android's usage history across all apps needs a special permission that the viewer
  grants in Android settings, which is not "not too much work" (FR-15).
- **Play:** the Play build carries the hook. The release that first carries it needs Play Console's accessibility
  declaration, and FR-2's screen is the in-app disclosure that declaration asks for.
- **QA:** QA may switch Ravilo's accessibility setting on and off on the Streamer for this work's tests, and leaves
  both switches off at the end. This covers the QA tickets of this spec's build slices and nothing else.

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
of Settings. FR-11 adds it to Apps.

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

FR-10 to FR-15 have a surface. The design round on this task draws them: where the Apps entry sits in the TV header,
the Apps screen, All apps in both orders, the pin menu, and the exits on the sign-in and can't-reach-the-server screens.

**FR-10 · An Apps entry.** While the hook is on (FR-3), Ravilo's TV header has an **Apps** entry. With the hook off,
the entry is not there.

**FR-11 · Apps.** Apps shows, in this order:

1. **Android settings.** It opens `Settings.ACTION_SETTINGS`.
2. **The stock home** (FR-5).
3. **The pinned apps**, in the order the viewer set (FR-14).
4. **See all apps**, which opens All apps (FR-15).

An app means one with a TV launcher entry (`MAIN` + `LEANBACK_LAUNCHER`), shown with its TV banner, or with its icon
and label where it has none. Ravilo itself is never listed. Selecting an app opens it with its leanback launch intent.
The apps are read from the TV each time Apps or All apps opens, so an app installed or removed shows up without a
restart. Both screens work with no network and with nobody signed in, because nothing in them comes from jellystructure.
The manifest gains the `<queries>` entry Android 11+ needs to see those apps. On a TV with no pins yet, Apps shows
items 1, 2 and 4 only.

**FR-12 · No dead end while the hook is on.** On every screen Ravilo can show in place of Home (sign-in, profile
picker, server setup, and the screen that says Ravilo can't reach the server), Apps is reachable in one press. So
Android's Wi-Fi settings are always reachable, and so is the stock home.

**FR-13 · Shared UI.** Apps and All apps are `:ravilo-ui` screens. Only reading the app list, launching an app,
Android settings or the stock home, and storing the pins and the last-opened record are Android `actual`s. Other platforms get no-op `actual`s and never show the entry.

**FR-14 · Pins, kept on the TV.**

- From All apps, the viewer can pin an app. From Apps, the viewer can unpin a pinned app or move it left or right.
  The way in is the menu or long press the design round draws.
- New pins go at the end.
- The pins are stored on the TV only, shared by every profile on it, kept across Ravilo updates, and never sent to
  jellystructure.
- A pinned app that is uninstalled drops out of Apps. If it is installed again it is not pinned.

**FR-15 · All apps, A–Z or recently opened.** All apps lists every app on the TV (FR-11), pinned ones included and
marked as pinned. It has two orders, and the screen remembers the last one used:

- **A–Z** by label. This is the default.
- **Recently opened.** Apps Ravilo opened, newest first, then every other app A–Z. Ravilo keeps a record on the TV of
  when it last opened each app, from Apps or All apps. Ravilo does not ask for Android's usage-access permission:
  an app opened some other way (voice, the stock home, a Cast) doesn't move up.

## Out of scope

- **Disabling the stock home over ADB** (the DEB-4906 fallback), and any HOME entry in Ravilo. These come back only as
  an amendment if FR-3 fails on the Streamer.
- Showing other apps' "continue watching". It needs a system permission Ravilo can't have.
- Android's usage history across all apps (FR-15 uses Ravilo's own record instead).
- **Switching HDMI inputs** from Ravilo. The remote's Input button keeps working.
- **Notifications and Google TV's quick-settings panel.** They stay on the stock home, one press away (FR-5).
- **Hiding apps per viewer.**

## Verification

Automated, in CI:

- `:ravilo-ui:desktopTest`: FR-1's section shows only where the platform says it is a TV. The FR-2 states: Android's
  switch off shows the one-step state and never *On*. FR-3's Home-inside-Ravilo goes to the top of Home, and Back at
  the top of Home does nothing with the hook on and exits with it off. FR-10's entry exists only with the hook on.
  FR-11's ordering (settings, stock home, pins, See all apps) with Ravilo left out. FR-14's pin, unpin, move, and
  an uninstalled pin dropping out, against a fake app list and a fake store. FR-15's two orders with an injected
  clock, and the remembered order. FR-12: Apps can be reached from
  sign-in and from the can't-reach screen.
- Also `:ravilo-ui:desktopTest`, as plain Kotlin in common code: FR-6's decision of what counts as the stock home's
  home screen, given a sequence of window changes, including FR-5's pass-through. FR-8's
  two-closes-in-five-minutes guard, with an injected clock.
- `:ravilo-ui:testDebugUnitTest` (Robolectric): FR-7. No merged-manifest component carries `HOME`, and the service
  is disabled by default with `canRetrieveWindowContent` false.

On a device, because a real accessibility service, a real Home key and a real boot can't run in CI: QA on the
**Google TV Streamer (via Kambi Mac)** only. Board row `google-tv-streamer-via-mac`. Its test plan covers FR-2 to FR-8 and FR-11 to FR-15. The owner
has allowed QA to switch Ravilo's accessibility setting on and off on the Streamer for these tests (DEB-4909 card).
QA leaves both switches off at the end. The Sony TVs are not tested.
