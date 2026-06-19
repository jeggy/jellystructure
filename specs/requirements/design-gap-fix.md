# Design Audit & Gap Fix

**Status:** Planned

All 40 jellystructure phases are implemented. This spec closes the remaining gaps between the
HTML design mockups (`design/app/`) and the Kotlin WASM frontend (`src/wasmJsMain/…/ui/`).

---

## Confirmed gap inventory

### Bucket 1 — Kotlin behind the HTML design (implement)

**1A — Settings: Notifications section (7th nav item)**
`settings.html` has a full `sect-notifications` card — webhook URL, 4 per-event mini-toggles
(Scan finished, No TMDB match, Write failed, Drift detected), "Send test notification" button.
`Settings.kt` has no Notifications nav item and no card. `readForm()` / `buildToml()` don't
include `notificationsWebhook`. `AppConfig.kt` / `ConfigApi.kt` `Behavior` have no per-event flags.
Backend only fires webhook on `scan_complete` in `MediaRoutes.kt`.

**1B — Settings: Scheduled rescan UI**
`settings.html` shows toggle + frequency dropdown (Daily/Weekly) + time-of-day picker + cron
preview. `Settings.kt` Scanning section has no `scan_interval_hours` input — the field exists in
`AppConfig.kt` (`scanIntervalHours: Int = 0`) and is used in `Main.kt` but is not surfaced in
Settings. `readForm()` doesn't read it; `buildToml()` doesn't emit it.

**1C — Settings: Per-library action buttons**
`settings.html` library rows have `.lib-scan` and `.lib-push` buttons. `Settings.kt`
`buildLibraryCardHtml()` has no such buttons. `MediaApi.startLibraryScan(jellyfinId)` already
exists. No per-library push endpoint exists — Push button will invoke `batchJellyfinPush()` (all
libraries) with button label "Push all to Jellyfin" to signal scope.

**1D — Settings: Tool status chips**
`settings.html` Scanning section header has a "media tooling: ffmpeg · ffprobe · mkvpropedit" chip
(`id="chk-tools"`). `Settings.kt` Scanning section has none. `ConfigApi.getHealthFull()` already
returns `HealthCheck` entries for these tools.

**1E — Activity: ffmpeg command in Now card**
`activity.html` shows a command label, args, and a `frame= fps= time= speed=` stats line.
`Activity.kt` `updateNowCard()` shows only "⟳ processing…". Backend logs `"ffmpeg: $cmd"` /
`"mkvpropedit: $cmd"` via `Logger.info(…, "track")` (→ `log_line` WS event, category "track").
Frontend already handles `log_line` events but doesn't parse them for the Now card. Live per-frame
stats are NOT available (ffmpeg stdout captured in batch); show command line only.

### Bucket 2 — HTML design behind Kotlin (update HTML)

**2A — Dashboard quick-action chips**
`index.html` shows 3 chips. `Dashboard.kt` renders 6 (adds: View items needing attention,
Sync NFOs to Jellyfin, Jellyfin library scan, View activity).

**2B — Seeding guard: chip vs banner**
`media.html` shows a chip `<span class="chip seeded" id="guard-chip">`. `MediaDetail.kt` injects a
full banner with torrent name + blocked/unreachable states into `#seeding-guard-banner`. Design
needs updating to the banner layout.

**2C — Forced subtitle toggle style**
`media.html` uses `.mini-toggle` (pill thumb style). `MediaDetail.kt` renders
`<button class="badge">` style toggles. Update HTML to match implementation.

**2D — Revert button element**
`media.html` uses `<span class="btn sm ghost revert-btn">`. `MediaDetail.kt` uses
`<button class="btn sm ghost history-revert-btn">`. Align to button element and class name.

**2E — Command palette**
`app-shell.js` has a design mock with `id="cmd-palette"`. `Shell.kt` injects `#cmd-palette-overlay`
with `#cmd-palette-input` and `#cmd-palette-list`. Sync JS mock IDs to match what Shell.kt injects.

### Bucket 3 — CSS token gap

**3A — `--ink-dim` undefined**
Referenced in `ravilo-config.html` (drag handle color) but absent from `wf.css`.
Add to both dark (default) and light theme blocks.

---

## Implementation

### Phase A — Data model: notification event flags

`AppConfig.kt` — add 4 boolean fields to `Behavior`:
```
notifyOnScanDone: Boolean = true      (@SerialName("notify_on_scan_done"))
notifyOnNoMatch: Boolean = false      (@SerialName("notify_on_no_match"))
notifyOnWriteFailed: Boolean = true   (@SerialName("notify_on_write_failed"))
notifyOnDrift: Boolean = false        (@SerialName("notify_on_drift"))
```

`ConfigApi.kt` — mirror identical fields in frontend `Behavior` data class.

### Phase B — Backend: webhook fan-out

`MediaRoutes.kt` — add helper `fireWebhook(cfg, payload)` reusing the existing
`posixSystem("curl -sf --max-time 10 -X POST … &")` pattern. Call conditionally for:
- `notifyOnNoMatch` — where TMDB ID cannot be found during scan
- `notifyOnWriteFailed` — where mkvpropedit/ffmpeg runner logs an error
- `notifyOnDrift` — where drift detection produces results

### Phase C — Settings.kt: four UI additions

**C1 — Notifications nav + section**
- 7th nav button `data-sect="sect-notifications"` between Cross-seed safety and Advanced
- Card contains: webhook URL input, 4 toggle rows, "Send test notification" button
- Module-level booleans: `notifScanDone`, `notifNoMatch`, `notifWriteFailed`, `notifDrift`
- Wire toggles using existing `updateToggle()` helper
- Add `sect-notifications` to `observeSections` call

**C2 — Scheduled rescan UI**
- "Scheduled rescan" toggle row in Scanning section
- When enabled: frequency select (Daily = 24h / Weekly = 168h) + time-of-day input
- Derives `scanIntervalHours` from selection; updates TOML preview on change
- Module-level vars: `scheduledRescanEnabled`, `rescanFrequency`, `rescanTime`

**C3 — Per-library Scan/Push buttons**
- In `buildLibraryCardHtml()`, add Scan + Push buttons at bottom of each card
- Scan handler: `MediaApi.startLibraryScan(lib.jellyfinId)` + inline badge feedback
- Push handler: `MediaApi.batchJellyfinPush()` (all-libraries scope)

**C4 — Tool status chips**
- In `populateForm()`, call `ConfigApi.getHealthFull()` on load
- Add `<span id="tool-status-chip">` to Scanning section header
- Populate with tool check results (ffmpeg/ffprobe/mkvpropedit) as ok/bad badges

**readForm() + buildToml() fixes**
- `readForm()`: include `notificationsWebhook`, 4 `notifyOn*` booleans, derived `scanIntervalHours`
- `buildToml()`: emit `scan_interval_hours` in `[behavior]`; add `[notifications]` section

### Phase D — Activity.kt: command in Now card

- Module-level `var lastToolCommand: String? = null`
- In `handleEvent()` log_line branch: if `category == "track"` and message starts with
  `"ffmpeg:"` or `"mkvpropedit:"`, set `lastToolCommand`
- In `updateNowCard()`: append command line below "⟳ processing…" in monospace small text
- In `resetNowCard()`: reset `lastToolCommand = null`

### Phase E — HTML design updates

`design/app/index.html`: add 3 missing chips (matching Dashboard.kt's 6 total)

`design/app/media.html`:
- Replace guard chip with full `#seeding-guard-banner` div (blocked/unreachable banner layout)
- Replace `.mini-toggle` in Forced column with `<button class="badge">` style
- Change Revert `<span>` to `<button class="btn sm ghost history-revert-btn">`

`design/app/app-shell.js`: update palette mock to `id="cmd-palette-overlay"` with
`#cmd-palette-input` and `#cmd-palette-list` children

### Phase F — CSS token

`design/app/wf.css`:
- Dark `:root`: `--ink-dim: rgba(238, 240, 247, 0.35);`
- Light block: `--ink-dim: rgba(15, 17, 26, 0.25);`

---

## Existing helpers to reuse

- `MediaApi.startLibraryScan(jellyfinLibraryId)` — `MediaApi.kt:195`
- `MediaApi.batchJellyfinPush()` — `MediaApi.kt:445`
- `ConfigApi.getHealthFull()` — `ConfigApi.kt`
- `updateToggle(id, on)` — `Settings.kt`
- `setInputValue() / getInputValue()` — `Settings.kt`
- `refreshTomlPreview(config)` — `Settings.kt`
- `posixSystem("curl …")` webhook pattern — `MediaRoutes.kt`

---

## Commit order

Phase A+B (data model + backend webhook) → Phase C (Settings.kt) → Phase D (Activity.kt)
Phase E+F (HTML + CSS) can be done in parallel with C/D — separate commit
