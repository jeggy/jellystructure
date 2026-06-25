# Phase 26 — qBittorrent Seeding Guard (FR-QB1)


## Problem

jellystructure will eventually run `mkvpropedit` automatically to set audio/subtitle default flags and
language tags. `mkvpropedit` rewrites MKV container headers, which changes file hashes and breaks
torrent piece verification. Users running cross-seed on top of their media library are particularly
exposed: cross-seed injects matched torrents into a qBittorrent seeder instance using hardlinks, so
both directly-seeded and cross-seeded copies of a file share the same inode. Modifying a file that is
actively being seeded corrupts that torrent's hash check.

## Current state (as-is)

- `mkvpropedit` is called only via explicit user action (Constitution §90) from five route handlers:
  `TrackRoutes.kt` (set default, set language — movies), `MediaRoutes.kt` (set default, set language —
  episodes), `TriageRoutes.kt` (movie + episode language triage).
- No pre-modification check exists to determine whether the target file is being seeded.
- `AppConfig` has no qBittorrent section; there is no outbound HTTP client for torrent clients.

## Requirements

### Config — new `[qbittorrent]` section

1. Add an optional `[qbittorrent]` table to `AppConfig`. When absent (the default), the guard is fully
   disabled and existing behaviour is unchanged.

   ```toml
   [qbittorrent]
   url      = "http://gluetun-seeder:8085"
   username = "admin"
   password = "..."
   enabled  = true          # set false to disable without removing credentials

   [[qbittorrent.path_mappings]]
   local  = "/mnt/media/jellyfin/movies"
   remote = "/media/movies"

   [[qbittorrent.path_mappings]]
   local  = "/mnt/series/jellyfin"
   remote = "/media/series"
   ```

   `path_mappings` translates jellystructure's host paths to the paths qBittorrent sees inside its
   container. Longest-matching `local` prefix wins. Required when jellystructure and qBittorrent run in
   separate containers with different mount points.

   Config fields must be serializable via ktoml and follow the existing `@SerialName("snake_case")`
   convention used throughout `AppConfig`.

### Backend — `QBittorrentClient`

2. New `QBittorrentClient` at
   `src/linuxX64Main/kotlin/dev/jellystructure/torrent/QBittorrentClient.kt`.
   Use `HttpClient(Curl)` — the same engine as `JellyfinClient` and `TmdbClient`.

   - `suspend fun login(config: QBittorrentConfig): String` — POST `/api/v2/auth/login` with
     `application/x-www-form-urlencoded` body (`username=&password=`); returns the `SID` cookie value.
     Throws on failure.
   - `suspend fun getTorrents(config: QBittorrentConfig, sid: String): List<QBTorrent>` — GET
     `/api/v2/torrents/info?filter=all` with `Cookie: SID=<sid>` header; deserialises JSON array.

   ```kotlin
   @Serializable
   data class QBTorrent(
       val hash: String,
       val name: String,
       val state: String,
       @SerialName("save_path")    val savePath: String,
       @SerialName("content_path") val contentPath: String,
   )
   ```

   Login once per `check` call (sessions are cheap; avoids stale-cookie complexity). Propagate
   exceptions so `SeedingGuard` can surface them as `Unreachable`.

### Backend — `SeedingGuard`

3. New `SeedingGuard` at
   `src/linuxX64Main/kotlin/dev/jellystructure/torrent/SeedingGuard.kt`.

   ```kotlin
   sealed class SeedingCheckResult {
       object Unconfigured : SeedingCheckResult()
       object Allowed : SeedingCheckResult()
       data class Blocked(val torrentName: String) : SeedingCheckResult()
       data class Unreachable(val reason: String) : SeedingCheckResult()
   }
   ```

   `suspend fun check(localFilePath: String, config: AppConfig): SeedingCheckResult`:

   1. If `config.qbittorrent == null` or `!config.qbittorrent.enabled` → return `Unconfigured`.
   2. Translate `localFilePath` using `pathMappings`: replace the longest matching `local` prefix with
      its `remote` counterpart. If no mapping matches, use the path as-is.
   3. Login + fetch torrents; catch any exception → return `Unreachable(exception.message)`.
   4. For each torrent, the file is in that torrent if:
      - `translatedPath == torrent.contentPath` (single-file torrent), **or**
      - `translatedPath.startsWith(torrent.contentPath + "/")` (directory torrent).
   5. If a match is found **and** `torrent.state` is one of `uploading`, `stalledUP`, `forcedUP`,
      `queuedUP`, `pausedUP` → return `Blocked(torrent.name)`.
   6. Otherwise → return `Allowed`.

   Torrents in `error` or `missingFiles` state are not blocked — the torrent is already broken.

4. **Fail-closed**: if qBittorrent is configured but unreachable or auth fails, return `Unreachable`
   and block the modification. Only `Unconfigured` (no `[qbittorrent]` section) passes through
   without a check.

### Backend — route guard at mkvpropedit call sites

5. Inject `SeedingGuard` into the five route handlers that call `MkvpropeditRunner`. Before each
   `mkvpropedit` invocation:

   ```kotlin
   when (val guard = seedingGuard.check(filePath, configStore.current)) {
       is SeedingCheckResult.Blocked ->
           return call.respond(HttpStatusCode.Conflict,
               mapOf("error" to "File is seeded by '${guard.torrentName}'"))
       is SeedingCheckResult.Unreachable ->
           return call.respond(HttpStatusCode.ServiceUnavailable,
               mapOf("error" to "qBittorrent unreachable: ${guard.reason}"))
       else -> Unit // Unconfigured or Allowed — proceed
   }
   ```

   Affected files: `TrackRoutes.kt`, `MediaRoutes.kt`, `TriageRoutes.kt`.

### Frontend

6. At each track-change call site in the WASM frontend (`POST .../tracks/default`,
   `POST .../tracks/language`), handle HTTP 409 and 503 by surfacing the `error` field from the JSON
   response body as an error message using the existing toast/notification mechanism. No new UI
   controls are needed.

### Wiring

7. Instantiate `QBittorrentClient` and `SeedingGuard` in `Main.kt` alongside `JellyfinClient` and
   `TmdbClient`. Pass `SeedingGuard` to the route registrations that call mkvpropedit.

## Config UI

Out of scope for this phase — qBittorrent credentials are managed in `config.toml` directly,
consistent with how other sensitive keys (`tmdb_v3_key`, `jellyfin_token`) are handled today.

## Invariants preserved

- Constitution §90 ("track flags are never changed automatically") remains in force until a future
  phase explicitly relaxes it. This phase adds the guard infrastructure that future automatic track
  changes will rely on.
- The guard is entirely opt-in via config; zero behaviour change when `[qbittorrent]` is absent.

## Out of scope

- Triggering cross-seed re-indexing after a modification.
- Automatically breaking hardlinks before modification.
- Support for other torrent clients (Deluge, rTorrent, etc.).
- A config UI for qBittorrent credentials.
