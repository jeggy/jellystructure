# Phase 221 — Tell the operator when an integration points at a dead endpoint

> Production, 24 hours to 2026-09-16:
>
> ```
> [WARN] Webhook delivery failed: Connection failed for request: CurlRequestData(url='http://<host>:8585', …). Reason: Could not connect to server (CURLE_COULDNT_CONNECT)   ×8
> [INFO] Webhook fired: {…}                                                                     ×8, one after each failure
> [WARN] Webhook: radarr webhook is deprecated (Phase 165) — set up the Jellyfin webhook in Settings instead   ×2
> ```
>
> Eight notifications went nowhere and each was logged as fired anyway. Radarr is still calling a
> route phase 165 retired. The Settings page shows a URL field and nothing else.

## Status

`✓ Built` — written 2026-09-16 from the live stue-TV sweep
(`specs/research-reports/stue-tv-test-sweep-2026-09-16.md`, finding F12), **implemented 2026-09-16**
(see §Implementation notes). Not dev-reviewed, not deployed. Backend + admin (Settings → Notifications,
Dashboard). `compileKotlinLinuxX64` + `compileKotlinWasmJs` clean; `WebhookStatusTest` (3) green.

**Numbering:** verified against `STATUS.md` on 2026-09-16 — admin taken through 218; 219 and 220 by
sibling specs the same day.

## What the code does (traced against `main`, 2026-09-16)

- `MediaRoutes.kt:2626-2641` `fireWebhook`: `runCatching { … post(url) … }.onFailure { warn }` and
  then, unconditionally, `Logger.info("Webhook fired: $payload")` — success is logged after a failure.
  Nothing counts failures; nothing remembers the last one.
- `behavior.notifications_webhook` (`AppConfig.kt:254`); Settings renders it as `#notif-webhook`
  (`Settings.kt:880`, saved at `:1473`). No test action, no delivery status. The production value points
  at a host:port with nothing listening; the operator has no way to know from the product.
- `WebhookRoutes.kt:435`: the deprecated *arr route acks 200, warns once per hit, and nudges Jellyfin.
  Radarr still hits it. The warning is the only trace, and it is in a log the operator does not read.
- Phase **212** built exactly the shape this needs: `AdvisorFinding` (`JellyfinAdvisorService.kt:415-424`)
  and `advisorFindingHtml` (`Settings.kt:1243/1252/1291`), with the silence rule — nothing renders where
  nothing is wrong.

## Requirements

**FR-221-1 — Log what happened.** `fireWebhook` logs "fired" only on success. A failure logs the target
host and the reason once (not the payload again), and records per target: consecutive failures, last
failure time and reason, last success time.

**FR-221-2 — A Test button, and a status line.** Settings → Notifications gains **Test** beside the URL
(POSTs a clearly-marked test payload, shows status code or connect error and elapsed ms inline) and a
standing line under the field: *"Last delivery 14:02 · failed · could not connect"* / *"Last delivery
09:15 · ok"* / *"Never delivered"*.

**FR-221-3 — A finding when deliveries keep failing.** When the last **3** deliveries to the
configured webhook all failed, an advisor-style finding (212's `AdvisorFinding`, rendered by
`advisorFindingHtml`) appears on Settings → Notifications and on the Dashboard: what is failing,
since when, the reason, and the field to fix. It clears itself on the next success. **Silent** while
deliveries succeed and silent when `notifications_webhook` is blank — unconfigured is not broken.

**FR-221-4 — The deprecated *arr route becomes a finding, not a log line.** Each hit records `last hit
at <time> from radarr|sonarr`. When hit within the last **7** days (30 until the 2026-09-25
amendment below), one finding: *"Radarr is still
configured to call jellystructure's deprecated webhook; the Jellyfin webhook (phase 165) has been
delivering since <date>. Remove the connection in Radarr → Settings → Connect."* Silent otherwise.
The per-hit WARN drops to one INFO per day per source.

**FR-221-5 — Nothing new is stored in config.** Delivery status and last-hit timestamps live in
memory with a small persisted record (the existing activity-log store or a two-column table), never
in `config.toml`.

## Non-goals

- Retrying notifications (a missed "scan done" is not a lost write).
- Retiring the *arr routes — a later phase, once FR-4's finding has stayed silent for a release.
- The Jellyfin webhook's own delivery probe (165 FR-165-7/8 already covers it).

## Verification

1. Unit: three failures raise the finding, one success clears it; a blank URL never raises it; the
   deprecated route's finding appears on a hit and not after 7 quiet days.
2. Live: point the webhook at a closed port, run a scan, see the status line and the finding; fix the
   URL, run again, see both clear.

## Open questions

- Whether "3 consecutive failures" should instead be "any failure in the last hour" — the former can
  take days to trigger on a quiet library. Recommendation: either condition raises it.

## Implementation notes (2026-09-16)

- **FR-221-1** — `fireWebhook` times the POST, records the outcome per target in `WebhookStatus`
  (`ops/WebhookStatus.kt`: consecutive failures, last failure time + reason, last success, last
  elapsed), logs *"Webhook fired"* only on a 2xx, and on failure logs the host and the reason once —
  never the payload again. A CancellationException is rethrown, not counted.
- **FR-221-2** — Settings → Notifications: the old *Send test notification* posted from the **browser**;
  **Test** now calls `POST /api/config/test-webhook`, so the server that will deliver the real ones does
  the test and reports status code or connect error plus elapsed ms inline; the delivery is recorded,
  and the standing line under the field (`GET /api/config/webhook-status`) reads *Last delivery 14:02 ·
  failed · could not connect* / *… · ok · 40 ms* / *Never delivered*.
- **FR-221-3** — `WebhookStatus.findings()` raises `webhook-failing` when the last 3 deliveries failed
  **or** any failure happened in the last hour without a later success (the open question's
  recommendation: either condition), in phase 212's `AdvisorFinding` shape; rendered by the same
  `advisorFindingHtml` (now `internal`) under the field and on the Dashboard (`#dash-findings`), and
  literally empty while deliveries succeed or the URL is blank.
- **FR-221-4** — `handleArrWebhook` records the hit per source and writes one INFO line per source per
  day (the per-hit WARN is gone); `arr-deprecated-<source>` is a finding for 30 days (7 since the
  2026-09-25 amendment) after the last hit,
  worded with the Jellyfin webhook's own last delivery (`RealtimeIngestService.lastWebhookReceivedAt`)
  when it has one.
- **FR-221-5** — everything above lives in memory with one small JSON file next to the database
  (`<dataDir>/webhook-status.json`); `config.toml` is untouched.
- **Verification 2 (live)** was not run — no deploy this session.

## Amendment (2026-09-25) — a week, not a month, and the note says when it will go

The owner read the Sonarr finding and asked the one question it left open:
*how long do I need to wait before this message goes away?* The note had no answer, no dismiss, and a
30-day window, so an operator who had already deleted the Sonarr connection would keep reading the
same instruction for a month. Owner decision: **7 days**. Changed with it, in `WebhookStatus.findings`:

- **`ARR_FINDING_WINDOW_MS` = 7 days.** A week is still long enough that an *arr which imports weekly
  keeps the note up between imports; a later hit restarts the clock, as before.
- **The note states its own expiry:** *"last call 2 days ago. This note clears itself 7 days after the
  last call, so in 5 days if Sonarr stops calling."* Rounded up, so it never says *in 0 days* while it
  is still showing.
- **Plain wording.** *"(phase 165)"* is gone from operator-facing text; *Where* names the connection by
  the URL an operator can see in Sonarr (*the Webhook connection whose URL ends in
  /api/webhooks/sonarr*); *Costs here* says what the call does (*asks Jellyfin to look for the file
  sooner*).
- **The Jellyfin half was wrong twice, now fixed.** (1) `RealtimeIngestService.lastWebhookReceivedAt`
  is epoch **seconds** and was passed to `findings()` as milliseconds, so a working Jellyfin webhook
  would have been reported as *delivering since 1970-01-21*; the call site in `ConfigRoutes` now
  converts. (2) The value is the **last** delivery, not the first, and it lives in memory only — so the
  wording is *"Jellyfin's webhook last delivered 3 h ago"*, and when it is null *"has not delivered
  since jellystructure last started"*, never *"has never delivered"*.
- **You lose** no longer claims *nothing* unconditionally. With a Jellyfin delivery on record it says
  so; without one it says *nothing, as long as Jellyfin's webhook works* and points at **Test delivery
  now** under Settings → Download tools → Realtime ingest.

`WebhookStatusTest`'s *arr case covers the 7-day cutoff, the rounded-up countdown, both Jellyfin
wordings and the *Where* URL.
