# Phase R152 — Receive & display Jellyfin server messages (DisplayMessage) as a top-right toast

## Goal
When an admin uses **Jellyfin → Dashboard → send message to an active client**, Ravilo (as a Jellyfin
client) receives the command over its session WebSocket and shows the message as a **toast in the top-right
corner**. On-screen time is derived from the message length using the client display formula below, unless
the server supplies an explicit non-zero `TimeoutMs`.

The Jellyfin dashboard exposes a **single message field** — so the toast is a **single line/block of text**,
not a header + body. (`DisplayMessage` technically also carries an optional `Header`, but the dashboard's
send-message UI does not populate it; treat the message as one string.)

## Background
Jellyfin delivers this as a **`GeneralCommand`** session message with `Name = "DisplayMessage"` and
`Arguments { Header, Text, TimeoutMs }` (all optional; `TimeoutMs` is often `0`/absent). Ravilo already
holds a session WebSocket to Jellyfin (used elsewhere for playstate); this phase adds a handler for that one
command and a small toast surface. No polling, no new endpoint.

## Requirements

### FR-R152-1 — Handle the DisplayMessage command
On a `GeneralCommand` with `Name == "DisplayMessage"`, read the message from `Arguments.Text` (the single
dashboard field) and `Arguments.TimeoutMs`, then enqueue a toast. `Arguments.Header` is not used by the
dashboard's single-field UI; if present it may be appended to the text, but the toast shows **one** text
block. An empty message is ignored.

### FR-R152-2 — Top-right toast surface
Render the toast **anchored top-right**, below the app bar. Multiple messages **stack vertically** (newest
nearest the top-right anchor, older ones below). Each toast is a **single block**: a small leading icon, the
**message text**, and a thin **countdown bar** along the bottom that drains over the display duration — no
separate header or kicker line (the dashboard only sends one field).

It slides/fades in from the right; it slides/fades out on dismiss. The base (resting) state is fully
visible — the entrance is a transition, not an animation that can strand the toast hidden if the animation
clock is throttled.

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
- Use the message `Text` for the length.
- **Server override:** if `Arguments.TimeoutMs` is present and `> 0`, use it verbatim instead of the
  calculated value. Otherwise use `calculateToastDurationMs`.

### FR-R152-4 — Dismissal
A toast dismisses when its duration elapses, or immediately when the user **selects/clicks** it (D-pad
Enter / pointer). Dismissing one does not disturb the others; the stack reflows.

## Implementation

| Layer | File | Change |
|---|---|---|
| Session WS | `RaviloSession` (Jellyfin socket handler) | Match `GeneralCommand` `DisplayMessage`; parse `Header`/`Text`/`TimeoutMs`; emit to a `serverMessages` flow. |
| Duration | `ToastDuration.kt` (new) | `calculateToastDurationMs(message)` exactly as above. |
| UI | `ServerMessageHost` (Compose overlay) | Top-right stacking column; per-toast enter/exit transition + countdown bar; auto-dismiss via the duration; dismiss on select. |

### Design reference (already built)
`design/ravilo/Ravilo TV.html`:
- `ravilo-app.js` — `calculateToastDurationMs(message)` (JS port), `showServerMessage(message[, timeoutMs])`
  (renders/stacks/auto-dismisses a single-text toast, server `timeoutMs > 0` wins), exposed as
  `window.raviloSendMessage(message[, timeoutMs])` (the admin→client entry point the WS handler feeds in
  production). A boot-time demo call simulates one incoming message.
- `ravilo.css` — `.rv-msgstack` / `.rv-msg` (top-right anchor, stack, slide/fade transition, single `.tx`
  line, gradient countdown `.rv-msg-bar`).

## Non-goals
- No reply / acknowledge affordance — DisplayMessage is one-way.
- No message history/inbox — toasts are transient.
- No change to other `GeneralCommand`s or the playstate protocol.
- No change to the in-player transient toast (`flash`) used for local actions.
