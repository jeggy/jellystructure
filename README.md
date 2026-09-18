# jellystructure

jellystructure owns the metadata for a Jellyfin library from outside Jellyfin. It writes the NFO
files, fetches the artwork, edits the audio and subtitle flags inside the media files, and then asks
Jellyfin to re-read what changed. Once it is running against a library you stop using Jellyfin's own
"Refresh Metadata" button.

The reason it exists is language. Jellyfin scrapes metadata in one configured language for the whole
server. A household with Faroese, Danish and English files wants each file described in its own
language, and there is no setting for that. jellystructure resolves the language per file instead,
by reading the audio tracks in physical track order and asking TMDB for a match on each in turn,
falling back to a configured global language if none match. A series resolves per episode.
Untagged audio tracks are surfaced in the UI for manual tagging rather than silently defaulting to
English. Track default flags are never changed automatically — that's a manual UI action.

## What it manages

- **NFO files.** Kodi-compatible XML, written atomically and never with `<lockdata>`, so Jellyfin
  stays free to re-read them.
- **Artwork.** Posters, backdrops, clearlogos, season posters, per-episode stills. A manually chosen
  image is locked and no scan will overwrite it.
- **Track flags.** Default/forced flags via `mkvpropedit` (header-only edit, not a remux); `ffmpeg
  -c copy` only when the container needs rewriting.
- **Intro and credits segments.** Chapter titles, an ffmpeg black-frame/silence heuristic, TMDB
  stinger data, and cross-episode Chromaprint fingerprinting via `fpcalc`, with an editor for fixing
  results by hand.
- **Age ratings.** Raw certifications from a dozen countries normalized to a 0–18 ladder.

It reads from TMDB, Jellyfin, and optionally Radarr, Sonarr, Jellyseerr, Bazarr and qBittorrent.

## Ravilo

The repository also contains Ravilo, a TV client for the same library. It's a thin renderer:
jellystructure composes the home layout, channel rows, search results and watched state; Ravilo
draws them; Jellyfin serves the video bytes. No scraping or metadata logic lives in the client.

Ravilo is Compose Multiplatform, building for Android TV, Android phone, browser (WASM) and Samsung
Tizen from one codebase. TV and phone ship as a single APK. The browser build is served by
`web-static-server`, a small Ktor static-file server with no dependency on the backend.

## Requirements

- Jellyfin, reachable over HTTP, with an admin account and an API token.
- A TMDB v3 API key.
- Read/write access to the media directories, mounted at the same paths the container sees.
- `ffmpeg`, `ffprobe`, `mkvpropedit` and `fpcalc` on `PATH` (all installed in the Docker image).

Jellyfin usually reports item paths with its own container prefix, so each library entry has a
`jellyfin_path` and a `local_path` for the scanner to translate between.

## Running it

```yaml
services:
  jellystructure:
    image: ghcr.io/jeggy/jellystructure:latest
    user: "1000:1000"
    ports:
      - "9505:9505"
    volumes:
      - ./config:/config
      - /mnt/media:/media:rw
    restart: unless-stopped
```

Open `http://localhost:9505`. Before `jellyfin_url` is set the app serves a setup screen. Sign in
with a Jellyfin admin account — there's no separate jellystructure account, and non-admin Jellyfin
users are rejected. The password is never stored; the session token comes from `/dev/urandom` and
lives in SQLite behind an `HttpOnly` cookie.

The container's UID/GID must match the owner of your media, or track edits will fail on write.
`/api/health` is unauthenticated and is what the image's `HEALTHCHECK` calls.

The Ravilo browser client is a second image, `ghcr.io/jeggy/ravilo-web:latest`, listening on 8080.
Both images are published on tagged releases as `MAJOR.MINOR`, plus `:latest` and a commit SHA tag.

## Configuration

Config is TOML at `/config/config.toml`, editable from the Settings page.
`config/config.example.toml` documents the shape. The minimum:

```toml
[api_keys]
tmdb_v3_key = ""
jellyfin_token = ""
jellyfin_url = ""

[language_rules]
fallback_language = "en"

[behavior]
overwrite_nfo = false
fetch_images = true
```

Library entries aren't written by hand — they're discovered from `GET /Library/VirtualFolders`
after a successful connection test, and you assign the local mount path per library in the UI.

Editing `config.toml` while the app is running does not work: config is memory-resident, and the
next save from the UI overwrites the file.

## Building from source

The backend is Kotlin/Native targeting `linuxX64` (no JVM at runtime); the build needs JDK 21 and
`libsqlite3-dev` for the link step.

```sh
./gradlew linkReleaseExecutableLinuxX64   # backend binary
./gradlew wasmJsBrowserDistribution       # admin frontend
./gradlew runDev                          # both, then run the backend locally
```

The Android modules are only included when `sdk.dir`/`ANDROID_HOME` is set, so a clone with no
Android SDK still configures and builds.

## Repository layout

```
src/                    backend (linuxX64) + admin frontend (wasmJs)
shared/                 code shared between backend and clients
ravilo-ui/              Compose UI shared by every Ravilo target
ravilo-android/         Android TV + phone, one APK
ravilo-web/             browser build
ravilo-screen/          receiver-only Tizen TV app (no navigation, driven entirely by the backend)
web-static-server/      static-file server for the browser build
design/                 HTML and CSS mockups, and the CSS the frontend actually ships
specs/                  requirements, one file per phase
scripts/                consistency checks (see below)
```

`design/app/wf.css` and `app.css` aren't mockup CSS that later gets reimplemented — Gradle's
`syncDesignAssets` task copies them into the distribution verbatim, so a new component class gets
added to `wf.css` and that's the whole styling step. No Tailwind or PostCSS.

The admin frontend is Kotlin/Wasm driving the DOM through `kotlinx.browser`. Compose for Web was
rejected because its canvas rendering breaks accessibility and CSS.

## How the project is developed

Every change starts as a numbered spec under `specs/requirements/` (jellystructure) or
`specs/ravilo/requirements/` (Ravilo), including bug fixes to already-shipped phases. `STATUS.md` is
the only place a phase's status lives. `specs/constitution.md` holds the architectural rules that
override everything else; `specs/plan.md` describes how the system is built today.

`scripts/check-phases.sh` fails if a spec file has no row in `STATUS.md` or a row points at a
missing spec file. The other scripts check CSS scoping, mobile CSS, and that the hand-written
language-to-country-code tables in the Ravilo clients agree with each other.

Dated research reports live in `specs/research-reports/` — investigations behind a decision, not
specifications, and some have gone stale.

## Caveats

This is software built for one household's library and then opened up. Assume the following:

- It writes to your media files. Start with a library you have backed up.
- `overwrite_nfo = true` means exactly that, and will replace NFO files you wrote yourself.
- Faroese, Danish and English are the languages that get exercised daily. Everything else is
  implemented but less travelled.
- The Tizen client covers 2016 to 2018 Samsung models and does not include the Discover screen.

## License

GPL-3.0. See [LICENSE](LICENSE).
