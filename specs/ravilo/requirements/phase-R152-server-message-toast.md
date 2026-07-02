# Phase R152 — Receive & display Jellyfin server messages (DisplayMessage) as a top-right toast

> Depends on **[Phase 110](../../requirements/phase-110-jellyfin-session-bridge.md)** (Jellyfin session
> bridge). Ravilo holds **no Jellyfin socket** — jellystructure receives the dashboard message on the
> per-device Jellyfin WebSocket it maintains, and relays it to the TV as a `server_message` event on the
> existing `/api/tv/events` socket. This corrects the earlier draft's premise that "Ravilo already holds
> a session WebSocket to Jellyfin" — it does not (and must not; R82–R85/R133 decoupled the app from
> Jellyfin for everything but the media bytes).

## Goal
When an admin uses **Jellyfin → Dashboard → send message to an active client**, the targeted Ravilo TV
shows the message as a **toast in the top-right corner**. On-screen time is derived from the message
length using the client display formula below, unless the server supplies an explicit non-zero
`TimeoutMs`.

The Jellyfin dashboard exposes a **single message field** — so the toast is a **single line/block of
text**, not a header + body. (`DisplayMessage` also carries an optional `Header`, but the dashboard's
send-message UI does not populate it; treat the message as one string.)

## Delivery path (server → TV)
1. Jellyfin dashboard: `POST /Sessions/{sessionId}/Message {Text, TimeoutMs?}` → Jellyfin wraps it as
   `GeneralCommand { Name: "DisplayMessage", Arguments: {Header, Text, TimeoutMs} }` and delivers it on
   **that session's WebSocket** — which Phase 110 holds per device.
2. jellystructure forwards it **device-addressed** over `/api/tv/events` as
   `server_message { text, header?, timeout_ms? }` (payload-bearing envelope, `acquisition_changed`
   precedent).
3. The app handles the event and enqueues a toast. Old app builds that don't know the type degrade
   harmlessly (unknown types fall through to the generic rev handler — `TvApiClient.kt:304-314`).

## Requirements

### FR-R152-1 — Handle the `server_message` event
On a `server_message` event, read the message from `text` and the optional `timeout_ms`, then enqueue a
toast. If `header` is present it may be prepended to the text ("Header — text"), but the toast renders
**one** text block. An empty/blank message is ignored. Toasts are shown on **every** screen, including
the player (mount at the app root).

### FR-R152-2 — Top-right toast surface
Render the toast **anchored top-right**, below the app bar. Multiple messages **stack vertically**
(newest nearest the top-right anchor, older ones below). Each toast is a **single block**: a small
leading icon, the **message text**, and a thin **countdown bar** along the bottom that drains over the
display duration — no separate header or kicker line.

It slides/fades in from the right; it slides/fades out on dismiss. The base (resting) state is fully
visible — the entrance is a transition, not an animation that can strand the toast hidden if the
animation clock is throttled.

### FR-R152-3 — Display duration formula
Compute on-screen time from the message text:
```
fun calculateToastDurationMs(message: String): Long {
    val baseTimeMs = 1500L     // Time to notice the toast appeared
    val msPerCharacter = 75L   // 75ms per character
    val minDurationMs = 3000L  // 3 seconds
    val maxDurationMs = 15000L // 15 seconds
    val calculatedTime = baseTimeMs + (message.length * msPerCharacter)
    return calculatedTime.coerceIn(minDurationMs, maxDurationMs)
}
```
- Use the message text (after any header prepend) for the length.
- **Server override:** if `timeout_ms` is present and `> 0`, use it verbatim instead of the calculated
  value. Otherwise use `calculateToastDurationMs`.

### FR-R152-4 — Dismissal
A toast dismisses when its duration elapses, or immediately when the user **selects/clicks** it (D-pad
Enter / pointer). Dismissing one does not disturb the others; the stack reflows. The toast stack must
**not steal D-pad focus** — selection targets it only when the user navigates to it explicitly; timeout
is the primary dismissal on TV.

## Implementation

| Layer | File | Change |
|---|---|---|
| Bridge (BE) | Phase 110 | Receives `GeneralCommand DisplayMessage` on the per-device Jellyfin WS; emits device-addressed `server_message` on `/api/tv/events`. |
| Events client | `shared/…/tv/TvApiClient.kt` (`connectEvents`, `:294-316`) | Add a `server_message` branch (decode the payload envelope) + an `onServerMessage` callback beside `onAcquisition`. |
| App wiring | `ravilo-ui/…/RaviloApp.kt` (events loop `:184-193`) | New `MutableSharedFlow<ServerMessage>` → `LocalServerMessages`; collected by the toast host. |
| Duration | `ravilo-ui/…/ToastDuration.kt` (new, commonMain) | `calculateToastDurationMs(message)` exactly as above (unit-testable). |
| UI | `ravilo-ui/…/components/ServerMessageHost.kt` (new) | Top-right stacking column; per-toast enter/exit transition + countdown bar; auto-dismiss via the duration; dismiss on select. Mounted in the RaviloApp root `Box` (`RaviloApp.kt:310-323`, above `AnimatedContent`) so it floats over every screen incl. the player. |

### Design reference (already built)
`design/ravilo/Ravilo TV.html`:
- `ravilo-app.js` — `calculateToastDurationMs(message)` (JS port), `showServerMessage(message[, timeoutMs])`
  (renders/stacks/auto-dismisses a single-text toast, server `timeoutMs > 0` wins), exposed as
  `window.raviloSendMessage(message[, timeoutMs])`. A boot-time demo call simulates one incoming message.
- `ravilo.css` — `.rv-msgstack` / `.rv-msg` (top-right anchor, stack, slide/fade transition, single `.tx`
  line, gradient countdown `.rv-msg-bar`).

## Non-goals
- No reply / acknowledge affordance — DisplayMessage is one-way.
- No message history/inbox — toasts are transient (a message sent while the TV is offline is dropped;
  Jellyfin only targets *active* sessions anyway).
- No change to other `GeneralCommand`s (Play/Playstate routing is R155) or the playstate protocol.
- No change to the in-player transient toast (`flash`) used for local actions.
