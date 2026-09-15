# Phase 165 — Realtime ingest driven by Jellyfin itself, not by Radarr/Sonarr (FR-ING2)

> Reported 2026-08-14: *"We currently have an autosync when something is finished within sonarr/radarr.
> But this ofc doesn't work, as jellystructure matches against jellyfin. So if something is fully
> available on disk now, we still need to wait for jellyfin to process it. So the current webhooks we
> have in radarr/sonarr are useless."* Proposed direction: use the **Jellyfin Webhook plugin**, verify
> it is installed, offer to install it, and retire the Radarr/Sonarr webhook setup from jellystructure.

**Status:** Planned — dev-authored, not yet built.

## 1. What exists today, and why the report is right

Two independent realtime-ingest paths ship today (both Phase 114):

**(a) `POST /api/webhooks/{sonarr,radarr}`** (`WebhookRoutes.kt`). A *arr `Download` event arrives with
a **host file path**. jellystructure maps it to a Jellyfin-visible path, calls
`notifyLibraryMediaUpdated`, then — because at that instant Jellyfin has no such item — enters a
**5-minute polling loop**, calling `findRecentItemByPath` every 10 seconds until the item appears
(`WebhookRoutes.kt:128-144`). On timeout it logs *"Jellyfin never picked up '…' within 5 min — leaving
for the next scheduled scan"* and gives up.

That loop is the whole problem in one place. The *arr event is genuinely early — it fires the moment
the file lands on disk — but jellystructure has nothing to *do* with a path until Jellyfin has scanned,
probed and matched it. The result is a fixed 5-minute guess window per import, a wrong-item risk on
path matching (this is the second attempt: the original `getItemByPath` returned an arbitrary item
because this Jellyfin ignores `?Path=` — see the bug memo), and a hard failure whenever Jellyfin's own
settle takes longer than five minutes (routine on a network mount, and routine for a whole-season
import).

**(b) `JellyfinLibraryListener`** — a persistent WS to Jellyfin's own `/socket`, reading
`LibraryChanged`'s `ItemsAdded`/`ItemsUpdated`, debounced 5s, enqueuing by real Jellyfin id. This is
already the *correct shape* — Jellyfin-sourced, id-based, no path guessing — and it is connected on the
live server. Its weakness is timing, not identity: `LibraryChanged` fires when Jellyfin **notices** the
file, which can be before it has finished probing and matching it, so the ingest can scan a
half-populated item. It also stops delivering entirely whenever the socket is down, with no catch-up.

So the report is accurate about (a), and (b) is a good foundation that has a real gap.

## 2. Why the Webhook plugin is the right answer (verified against the live server)

Verified live, 2026-08-14, against `https://jellyfin.example.net` (Jellyfin **10.11.11**):

- `GET /Plugins` — the Webhook plugin is **not installed**. (Installed: AudioDB, File Transformation,
  Moonfin, MusicBrainz, OMDb, Studio Images, TMDb.)
- `GET /Packages` — it **is** available in the default repository: name `Webhook`, GUID
  `71552a5a5c5c4350a2aeebe451a30173`, category `Administration`, latest version **21.0.0.0** targeting
  ABI `10.11.8.0` — compatible with 10.11.11.
- The install and configure endpoints both exist on this server's OpenAPI:
  `POST /Packages/Installed/{name}?assemblyGuid=&version=&repositoryUrl=`,
  `GET|POST /Plugins/{pluginId}/Configuration`, and `POST /System/Restart`.

And the decisive property, from the plugin's own source (`ItemAddedNotifier/ItemAddedManager.cs`):
**an added item is queued and only dispatched once it has `ProviderIds` populated**, re-queued
otherwise, up to `MaxRetries = 10`. In other words the plugin fires *after Jellyfin has finished
identifying the item* — exactly the signal both existing paths are missing. Path (a) approximates it
with a 5-minute poll; path (b) fires before it.

Destination shape (`Destinations/Generic/GenericOption.cs` + `Destinations/BaseOption.cs`): a
**Generic** destination has `WebhookName`, `WebhookUri`, `EnableWebhook`, `NotificationTypes[]`,
`UserFilter[]`, per-type toggles `EnableMovies` / `EnableEpisodes` / `EnableSeries` / `EnableSeasons`
/ `EnableAlbums` / `EnableSongs` / `EnableVideos`, a **Handlebars** `Template`, `SendAllProperties`,
`TrimWhitespace`, `SkipEmptyMessageBody`, plus `Headers[]` and `Fields[]`. That is everything needed to
POST a small JSON body containing `ItemId` and `ItemType` to a single jellystructure URL, filtered to
Movies + Episodes + Series only.

## 3. Functional requirements

### FR-165-1 — New inbound route `POST /api/webhooks/jellyfin`

Open route (same family as the existing *arr ones — Jellyfin's webhook sender cannot present a cookie
or API key), authenticated by the existing per-install `ingest.webhook_secret` as a query param,
constant-time compared via `constantTimeEquals`.

Body is read defensively (same posture as `handleArrWebhook` — never bind a strict DTO to a
third-party sender): extract `ItemId` and `ItemType`, accept both the templated minimal body this
phase configures and the plugin's `SendAllProperties` shape. Respond `200` immediately and hand the
id to `RealtimeIngestService.enqueue(itemId)` on `appScope` — the id is already a real Jellyfin id, so
**no path mapping and no polling loop is involved at all**. The service's existing 8s burst debounce
handles a season import firing one event per episode.

Unknown/unsupported item types are acknowledged and ignored (never 4xx — an unacknowledged webhook
makes the plugin retry).

### FR-165-2 — Detect the plugin, and offer to set it up

`GET /api/settings/ingest-status` (existing, cookie-gated) is widened to report the Jellyfin side:

```
{ webhook_secret, realtime, listener_connected, last_event_at,
  jellyfin: { reachable, version, plugin_installed, plugin_version, plugin_available_version,
              destination_configured, destination_url, restart_pending } }
```

- `plugin_installed` / `plugin_version` from `GET /Plugins` (match on the GUID, not the name).
- `plugin_available_version` from `GET /Packages` (so the card can say "installable" vs "not offered
  by this server's repositories").
- `destination_configured` / `destination_url` from `GET /Plugins/{guid}/Configuration`, by looking for
  a `GenericOptions` entry whose `WebhookUri` points at this jellystructure install.

### FR-165-3 — One-click **Set up Jellyfin webhook**

A single admin action, `POST /api/settings/ingest/setup-jellyfin`, that does as much as it safely can
and reports honestly on what is left:

1. If the plugin is not installed: `POST /Packages/Installed/Webhook?assemblyGuid=71552a5a5c5c4350a2aeebe451a30173`.
   Report back that **Jellyfin must restart** before the plugin loads.
2. Offer — but never perform silently — the restart. `POST /System/Restart` is available, but
   restarting the household's media server is an outward-facing, disruptive action; it must be its own
   explicitly-labelled button ("Restart Jellyfin now"), never a side effect of "set up".
3. Once the plugin is loaded: `GET /Plugins/{guid}/Configuration`, add or update **one**
   `GenericOptions` entry named `jellystructure` (matching by `WebhookName`, so re-running is
   idempotent and never duplicates), then `POST` the whole configuration back. The entry:
   - `WebhookUri` = `{this install's base URL}/api/webhooks/jellyfin?secret={ingest.webhook_secret}`
   - `NotificationTypes` = `["ItemAdded"]`
   - `EnableMovies` / `EnableEpisodes` / `EnableSeries` = true; seasons/albums/songs/videos false
   - `Template` = a minimal Handlebars body: `{"ItemId":"{{ItemId}}","ItemType":"{{ItemType}}"}`
   - `SendAllProperties` = false, `EnableWebhook` = true
4. Never touch any other destination in the configuration. The admin may already have Discord/Gotify
   destinations set up; this must read-modify-write, preserving everything it did not author.

**The exact JSON shape of the Webhook plugin's configuration document as returned by
`GET /Plugins/{guid}/Configuration` on 10.11.11 must be confirmed live before this is built** — the
endpoint serialises the plugin's own C# configuration class, and the casing/nesting is
plugin-version-specific. This is the one genuinely unverifiable-from-here piece of this phase.

If any step fails, the card falls back to **manual instructions**: show the destination URL with a
Copy button and the exact field values, so an admin can set it up in Jellyfin's own plugin page.
That fallback is not optional — it is what makes this phase safe to ship before the shape above is
confirmed.

### FR-165-4 — Settings ▸ Download tools ▸ Realtime ingest, rewritten

Replace the current card (`Settings.kt:361-383`, `design/app/settings.html`) entirely:

- **Remove** the Sonarr webhook URL field, the Radarr webhook URL field, and the paragraph of
  Radarr/Sonarr **Settings → Connect** instructions. jellystructure stops telling anyone to configure
  *arr webhooks.
- **Add** a Jellyfin webhook status block with four states, each with its own single next action:
  - *Jellyfin unreachable* — nothing offered, points at the Connections tab.
  - *Plugin not installed* — **Install the Webhook plugin** (+ the restart note).
  - *Plugin installed, restart pending* — **Restart Jellyfin now** (explicit, destructive-labelled) or
    "I'll restart it myself".
  - *Plugin installed, destination missing/stale* — **Set up Jellyfin webhook**.
  - *Configured* — a green badge, the destination URL, last-event-received timestamp, and a
    **Send a test** action.
- Keep the existing Jellyfin change-feed listener line, re-worded from "Also listening for manual
  library changes" to what it now is: a **fallback** (see FR-165-5).

### FR-165-5 — Keep the WS listener, demoted to a fallback

Do **not** delete `JellyfinLibraryListener`. It is the only path that survives the Webhook plugin being
absent, disabled, mid-restart, or mis-templated, and it costs one socket. It keeps working exactly as
today; the ingest's existing debounce means a webhook and a `LibraryChanged` event for the same item
coalesce into one run. The Settings copy must simply stop presenting it as the primary mechanism.

### FR-165-6 — Retire the *arr webhook routes

`POST /api/webhooks/sonarr` and `POST /api/webhooks/radarr` stay **routed** in this phase but become
deprecated: they log one `Logger.warn` per call naming this phase, and (recommended) skip the
5-minute path-poll entirely, doing only the `notifyLibraryMediaUpdated` nudge and letting the Jellyfin
webhook / change feed deliver the actual ingest. Existing installs already have those URLs pasted into
their *arr instances, and silently 404ing them would turn a working-ish path into a broken one at
upgrade time. Removal of the routes is a follow-up phase once the Jellyfin path is confirmed live.

Nothing else depends on the *arr webhooks: acquisition tracking polls (`[acquisition] poll_seconds`),
and `ArrRescanService` is outbound-only. Verified.

## 4. Non-goals

- Any use of the plugin's other notification types (playback, auth, task-completed). `ItemAdded` only.
- Replacing the Jellyfin change-feed listener.
- Installing or configuring anything in Radarr/Sonarr on the admin's behalf.
- Handling item **removal**. Removal detection stays scan-only (the Phase 95 non-destructive
  invariant); `ItemAdded` has no counterpart here.

## 5. Open questions

1. **Configuration document shape** (FR-165-3 step 3) — must be confirmed live post-install before the
   one-click path can be trusted. Until then the manual-instructions fallback is the shipped path.
2. **What base URL does jellystructure advertise?** The current *arr card derives the URL from the
   browser's own address, which is right for a human copying it but wrong for a server-to-server
   write. Jellyfin must be able to reach jellystructure — likely `http://192.0.2.20:9505` on this
   install, not the admin's public hostname. Needs an explicit, editable "where Jellyfin should reach
   this install" field (same shape as Towo's "where runners connect" URL override, Phase 162).
3. Whether to also gate on the plugin's per-destination `UserFilter` — `ItemAdded` is not user-scoped,
   so an empty filter is expected to be correct; confirm on the live install.
4. Does 10.11.11's `ItemAdded` fire for an **upgraded** file (the *arr `Upgrade` case), or only for a
   genuinely new library item? If not, the change-feed listener's `ItemsUpdated` branch remains the
   only signal for an in-place quality upgrade — worth confirming, since the current *arr card
   explicitly asks operators to enable *On Upgrade*.

## 6. Verification

- With the plugin installed and configured, import one movie and one full season through Radarr/Sonarr
  and confirm: an `/api/webhooks/jellyfin` hit per item, a single coalesced ingest run per title in
  Activity, and the title visible in Ravilo without any 5-minute wait.
- Disable the destination in Jellyfin and confirm the change-feed listener still eventually ingests
  (fallback intact).
- Re-run **Set up Jellyfin webhook** twice and confirm exactly one `jellystructure` destination exists
  and no other destination was modified.
- Confirm the Settings card renders each of the five states correctly against a Jellyfin with the
  plugin absent, present-but-unconfigured, and configured.

## 7. Amendment 2026-08-14 — live delivery failure found, "Test this URL" replaced with a real E2E probe

**Reported live:** a full season of *Minty Mouse Treehouse* imported via Sonarr and appeared in Jellyfin
normally, but never reached jellystructure — no `/api/webhooks/jellyfin` hit at all, and no entry logged
by jellystructure (not even the "no ItemId" warning `handleJellyfinWebhook` logs on a malformed body).

**Investigation (this session), all confirmed live:**
- Network path Jellyfin → jellystructure: confirmed working — `docker exec` into the `jellyfin`
  container and curling the exact configured destination URL succeeded (`200`), and jellystructure
  logged the hit.
- The plugin's own saved configuration (`GET /Plugins/{id}/Configuration`): confirmed correct —
  `WebhookName=jellystructure`, right `WebhookUri`+secret, `EnableWebhook=true`,
  `EnableMovies`/`EnableEpisodes`/`EnableSeries=true`, `NotificationTypes=["ItemAdded"]`.
- A full Jellyfin restart (`POST /System/Restart`) did not fix it.
- **Decisive test**: temporarily added `TaskCompleted` (fires immediately on any scheduled-task
  completion — no `ItemAdded` batching/retry involved, same `WebhookSender`/`GenericClient` send path)
  to the `jellystructure` destination's `NotificationTypes`, then manually triggered the plugin's own
  "Webhook Item Added Notifier" task (`POST /ScheduledTasks/Running/{id}`, confirmed via `GET
  /ScheduledTasks` to run automatically every 30s regardless). Real, automatic completions of that task
  fired dozens of times over ~2 hours (both before and after the restart) with the destination correctly
  subscribed — **zero deliveries ever reached jellystructure**. Reverted the temporary `NotificationTypes`
  change afterward; permanent config untouched.
- Read the plugin's actual source (`ItemAddedManager.cs`, `WebhookSender.cs` on
  `jellyfin/jellyfin-plugin-webhook`) to rule out config-shape mistakes: dispatch only silently drops an
  item on `GetItemById` returning null (not our case — items were correctly identified in Jellyfin) or
  after 10 failed provider-id retries (also not our case); `SendNotification` only requires
  `NotificationTypes.Contains(type)` + `EnableWebhook` + the matching `Enable*` flag, all of which our
  config satisfies, and logs (doesn't silently swallow) any send exception.
- Attempted to decompile the installed plugin DLL (`monodis`/`ikdasm`) to find the actual defect;
  blocked by missing reference assemblies in this environment. **Root cause inside the plugin binary
  could not be pinned down further** — everything on jellystructure's side is demonstrably correct and
  reachable, but the plugin, as installed on this specific server, does not deliver.

**Separately found while investigating — a real UI gap, worth fixing regardless of the above:** the
Settings card's only "last event received" signal (`IngestStatus.lastEventAt` / `last_event_at`) is
sourced **exclusively** from [[JellyfinLibraryListener]] — the *fallback* WS change-feed listener (FR-165-5)
— never from the primary `/api/webhooks/jellyfin` route itself. An admin has had **no way to tell
whether the primary Jellyfin-plugin path has ever actually delivered anything**, since the fallback's
own occasional traffic could read as "ingest is working" even while the primary path is silently dead —
exactly the confusion that let this go unnoticed.

**Decision, given the live report "we want to catch this as part of our own setup process, not require
manual forensics every time":**

### FR-165-7 — Track the primary webhook's own delivery, separately from the fallback

`RealtimeIngestService` gains an in-memory `@Volatile var lastWebhookReceivedAt: Long? = null`, set by
`handleJellyfinWebhook` the instant a request passes the secret check — **before** the item-type filter,
so even a probe/test payload that gets filtered out downstream still counts as "the plugin reached us."
`IngestStatus` gains `last_webhook_received_at` alongside the existing (renamed in the UI, not the wire
field) fallback `last_event_at`, and the Settings card shows both, clearly labelled: "Jellyfin webhook: …"
vs "Fallback (change feed): …" — so this exact blind spot can never recur.

### FR-165-8 — "Test this URL" becomes a real end-to-end probe, not a self-loop

The existing test (jellystructure curling its own configured destination URL) proves only that the URL
is well-formed and that jellystructure can reach *itself* — it cannot catch the failure mode found live
above, where the network and config are both fine but Jellyfin's plugin still never delivers. Replace it
with `POST /api/settings/ingest/test-live`, using the exact technique that found the bug:

1. Require Jellyfin reachable + plugin installed + destination configured (else `400` with a clear
   "set up the webhook first" message — this is a *live delivery* test, not a setup step).
2. Record `before = realtimeIngest.lastWebhookReceivedAt`.
3. Read-modify-write the plugin configuration: if `TaskCompleted` isn't already in the destination's
   `NotificationTypes`, add it (keep every other field and every other destination untouched).
4. Look up the plugin's own "Webhook Item Added Notifier" task via `GET /ScheduledTasks` (match on
   `Key == "WebhookItemAdded"`, never hardcode the task's `Id` — it is not guaranteed stable across
   installs) and trigger it (`POST /ScheduledTasks/Running/{id}`) — cheap, harmless, always completes.
5. Poll `realtimeIngest.lastWebhookReceivedAt` for up to 10s for a change from `before`.
6. **Always** revert `NotificationTypes` to its exact original value (even on failure/timeout) —
   never leave the admin's config in a different state than they started with.
7. Report the honest, specific result: delivered → "Jellyfin successfully delivered a live webhook to
   this address." / not delivered → "Jellyfin's Webhook plugin did not deliver anything, even though the
   configuration and network path both look correct — this may be a plugin issue on the Jellyfin side.
   The fallback change-feed listener below will still eventually pick up new items."

### Non-goals (this amendment)

- Does not attempt to fix the plugin itself (out of jellystructure's control) or work around it with a
  from-scratch webhook re-implementation.
- Does not add a periodic *automatic* re-probe (e.g. daily) — this amendment adds the tooling to check
  on demand and to see the primary path's own freshness at a glance; an unprompted background probe that
  mutates Jellyfin's plugin config on a schedule is a bigger step than what was asked for here.
