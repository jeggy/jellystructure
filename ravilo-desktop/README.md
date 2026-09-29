# Ravilo for the Mac

The Mac app: Compose Desktop on the JVM, showing `ravilo-ui`'s TV layout driven by a keyboard and a mouse
(R328), playing through AVPlayer (R329), casting with its own Cast v2 client (R330), shipped as a `.dmg` on every
GitHub release (R331).

- `src/desktopMain/` — the window, the menu bar, closing and quitting, About.
- `native/` — `libravilo-mac.dylib`, the Swift library (the Keychain, the Computer Name, the player, Now Playing,
  display sleep, Bonjour). Only a Mac with Xcode's command-line tools builds it (`buildMacNative`); anywhere else the
  app runs without it.
- `icons/` — `ravilo.icns` for the bundle, `ravilo.png` for Linux.

## Running it

```
./gradlew :ravilo-desktop:run
```

works on a Mac and on Linux. On Linux, and on a Mac without the Swift library, the app browses and signs in but
plays nothing, and keeps its tokens in `tokens.json` (mode `0600`) instead of the Keychain. `XDG_DATA_HOME` (or
`-Dravilo.data.dir`) gives a second copy its own sign-in.

**A development build asks for the Keychain.** A Keychain item remembers the signing identity of the app that
made it. Releases are signed with one certificate of our own (R331), so an installed Ravilo never asks; a build from
`gradle run` is signed differently, so macOS asks *Ravilo wants to use your confidential information* — expected.

**Discovery needs the packaged app.** macOS grants Local Network access to an app bundle, not to a bare `java`
process: develop Chromecast discovery against `createDistributable`'s `Ravilo.app`, not against `run` (R330).

## Checking a bundle

```
Ravilo.app/Contents/MacOS/Ravilo --self-test
```

loads the Swift library, reads the version and the data directory, prints one line and exits 0 — without a window.
The release workflow runs it before uploading (R331 FR-R331-4).
