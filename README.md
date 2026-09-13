# jellystructure

jellystructure owns the metadata for a Jellyfin library from outside Jellyfin. It writes the NFO
files, fetches the artwork, edits the audio and subtitle flags inside the media files, and then asks
Jellyfin to re-read what changed. Once it is running against a library you stop using Jellyfin's own
"Refresh Metadata" button.

The reason it exists is language. Jellyfin scrapes metadata in one configured language for the whole
server. A household with Faroese, Danish and English files wants each file described in its own
language, and there is no setting for that. jellystructure resolves the language per file instead:

1. `ffprobe` the file and read the audio tracks in physical track order.
2. Ask TMDB for the first track's language. If TMDB has that title in that language, use it.
3. Otherwise try the next track's language, then the next.
4. If none of them return anything, fall back to the configured global language (default `en`).

A series resolves per episode, and `tvshow.nfo` takes the majority language of its episodes. Files
with untagged audio tracks are surfaced in the UI so you can tag them by hand rather than having them
silently fall through to English.

Track default flags are never changed automatically. That is a manual action in the UI, because
reordering someone's audio tracks behind their back is not a metadata operation.

## What it manages

- **NFO files.** Kodi-compatible XML (`movie.nfo`, `tvshow.nfo`, `episodedetails.nfo`), written
  atomically to a `.tmp` file and renamed. Never written with `<lockdata>`, so Jellyfin stays free to
  re-read them.
- **Artwork.** Posters, backdrops, clearlogos, season posters, per-episode stills. A manually chosen
  image is locked and no scan will overwrite it.
- **Track flags.** Default and forced flags on MKV files via `mkvpropedit`, which is a header-only
  edit rather than a remux. `ffmpeg -c copy` is used only when the container needs rewriting, and a
  re-encode never happens automatically.
- **Intro and credits segments.** Chapter titles, an ffmpeg black-frame and silence heuristic, TMDB
  stinger data, and cross-episode Chromaprint fingerprinting via `fpcalc`, with an editor for fixing
  the results by hand.
- **Age ratings.** Raw certifications from a dozen countries ("TV-MA", "Btl", "Från 15 år") normalized
  to a 0 to 18 ladder so filtering works across a mixed library.

It reads from TMDB, Jellyfin, and optionally Radarr, Sonarr, Jellyseerr, Bazarr and qBittorrent. The
qBittorrent connection exists to check whether a file is still seeding before anything touches it.

## Ravilo

The repository also contains Ravilo, a TV client for the same library. It is a thin renderer:
jellystructure composes the home layout, the channel rows, the search results and the watched state,
Ravilo draws them, and Jellyfin serves the video bytes. There is no scraping or metadata logic in the
client at all.

Ravilo is Compose Multiplatform and builds for Android TV, Android phone, browser (WASM) and Samsung
Tizen from one codebase. The Android TV and phone entry points ship as a single APK. The browser build
is served by `web-static-server`, a Ktor static-file server small enough to have no dependency on the
backend.

## Requirements

- Jellyfin, reachable over HTTP, with an admin account and an API token.
- A TMDB v3 API key.
- Read and write access to the media directories, mounted at the same paths the container sees.
- `ffmpeg`, `ffprobe`, `mkvpropedit` and `fpcalc` on `PATH`. The Docker image installs all four.

Jellyfin usually reports item paths with its own container prefix. Each library entry has a
`jellyfin_path` and a `local_path` so the scanner can translate between the two.

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

Open `http://localhost:9505`. Before `jellyfin_url` is set the app serves a setup screen; that screen
404s once config exists. Sign in with a Jellyfin admin account. There is no separate jellystructure
account, and non-admin Jellyfin users are rejected. The password is never stored; the session token
comes from `/dev/urandom` and lives in SQLite behind an `HttpOnly` cookie.

The UID and GID matter. The container runs as a non-root user and needs to be the same UID as the one
that owns your media, or track edits will fail on write.

`/api/health` is unauthenticated and is what the image's `HEALTHCHECK` calls.

The Ravilo browser client is a second image, `ghcr.io/jeggy/ravilo-web:latest`, listening on 8080.

Both images are published on tagged releases as `MAJOR.MINOR`, plus `:latest` and a commit SHA tag.

## Configuration

Config is TOML at `/config/config.toml`, and everything in it is editable from the Settings page.
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

Library entries are not written by hand. They are discovered from
`GET /Library/VirtualFolders` after a successful connection test, and you assign the local mount path
per library in the UI.

Editing `config.toml` while the app is running does not work. The config is memory-resident and the
next save from the UI overwrites the file.

## Building from source

The backend is Kotlin/Native targeting `linuxX64`, so there is no JVM at runtime; the build needs a
JDK 21 and `libsqlite3-dev` for the link step.

```sh
./gradlew linkReleaseExecutableLinuxX64   # backend binary
./gradlew wasmJsBrowserDistribution       # admin frontend
./gradlew runDev                          # both, then run the backend locally
```

The Android modules are only included when `sdk.dir` is set in `local.properties` or `ANDROID_HOME`
is set in the environment, so a clone with no Android SDK still configures and builds.

## Repository layout

```
src/                    backend (linuxX64) + admin frontend (wasmJs)
shared/                 code shared between backend and clients
ravilo-ui/              Compose UI shared by every Ravilo target
ravilo-android/         Android TV + phone, one APK
ravilo-web/             browser build
ravilo-tizen/           Samsung TV models from 2016 to 2018
web-static-server/      static-file server for the browser build
design/                 HTML and CSS mockups, and the CSS the frontend actually ships
specs/                  requirements, one file per phase
scripts/                consistency checks (see below)
```

`design/app/wf.css` and `app.css` are not mockup CSS that later gets reimplemented. The Gradle
`syncDesignAssets` task copies them into the distribution verbatim and `index.html` links them, so a
new component class gets added to `wf.css` and that is the whole styling step. There is no Tailwind
or PostCSS pipeline.

The admin frontend is Kotlin/Wasm driving the DOM through `kotlinx.browser`. Compose for Web was
rejected because its canvas rendering breaks accessibility and CSS.

## How the project is developed

Every change starts as a numbered spec under `specs/requirements/` (jellystructure) or
`specs/ravilo/requirements/` (Ravilo), including bug fixes to already-shipped phases. `STATUS.md` is
the only place a phase's status lives, currently 191 jellystructure rows and 198 Ravilo rows.
`specs/constitution.md` holds the architectural rules that override everything else, and
`specs/plan.md` describes how the system is built today.

`scripts/check-phases.sh` fails if a spec file has no row in `STATUS.md` or a row points at a spec
file that no longer exists. The other scripts in that directory check CSS scoping, mobile CSS, and
that the hand-written language-to-country-code tables in the Ravilo clients agree with each other.

Eighteen dated research reports live in `specs/research-reports/`. They are investigations behind a
decision, not specifications, and some have gone stale.

## Caveats

This is software built for one household's library and then opened up. Assume the following:

- It writes to your media files. Start with a library you have backed up.
- `overwrite_nfo = true` means exactly that, and will replace NFO files you wrote yourself.
- Faroese, Danish and English are the languages that get exercised daily. Everything else is
  implemented but less travelled.
- The Tizen client covers 2016 to 2018 Samsung models and does not include the Discover screen.

## License

GPL-3.0. See [LICENSE](LICENSE).
