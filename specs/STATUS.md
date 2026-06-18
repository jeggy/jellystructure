# Status

Living record of where the project stands. Update this whenever a phase completes or direction
changes, so the next session can pick up without re-deriving context.

_Last updated: 2026-06-18_

## Snapshot

- **Phases 0–13: ✓ Done.** Core system complete: Jellyfin-driven discovery, ffprobe track data,
  TMDB matching, NFO writing, artwork, per-file/per-episode language resolution, track editing,
  triage, live WebSocket scans, persistent/resumable scan state, theme picker, dirty-indicator diff
  popups, external links, language pickers, simplified series language UI, and targeted single-item
  sync.
- **Phases 14–18: Planned.** Not started. See [`requirements/`](requirements/).
- **Next up: Phase 14** — Fix library path matching for movies (FR-B1). Movies currently fail to
  match a library during scans (`[WARN] No matching library for '/media/movies/...'`) because the
  `jellyfinPath` vs `localPath` prefix check fails when the two containers mount media at different
  paths.

## Recent work (git)

- Commented out `<lockdata>` writes in NFO files (under evaluation — constitution still mandates it; revisit before Phase 14 sign-off).
- Adjusted config file paths and improved Jellyfin API error logging.
- Added diagnostic tools, batch Jellyfin sync, improved permission handling.

## Known issues / open threads

- **Library path matching** — the Phase 14 root cause; blocks movie scans on mismatched mounts.
- **`<lockdata>` disabled** — temporarily commented out in `NfoWriter`. Decide whether to restore (constitution §"NFO Files") or formally amend the constitution.
- **Port free-check logic** — unresolved `TODO` noted in the last commit message; revisit.

## How to work here (spec-driven flow)

1. Read [`constitution.md`](constitution.md) — the rules that always hold.
2. Read the relevant phase spec in [`requirements/`](requirements/).
3. Check [`plan.md`](plan.md) for where the code lives and the current API/UI surface.
4. Break the phase into steps in [`tasks.md`](tasks.md), implement, and keep that file current.
5. On completion: move the phase spec to `requirements/archive/`, flip its status, and update this file.
