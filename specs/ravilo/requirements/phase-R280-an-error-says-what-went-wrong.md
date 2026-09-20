# Phase R280 — an error says what went wrong, in your language

> R279 found seven screens drawing a store's raw error sentence and wrote: *"This cannot be fixed by
> extraction: the stores are plain classes with no language, so a store must carry a **cause** and let
> the screen say the sentence. R237 already did exactly this for playback. Its own phase."*
>
> This is that phase. Reading the code to write it turned up three things R279's sweep did not: it is
> **ten** render sites over **thirteen** stores, not seven over nine; what they draw is not an English
> sentence but **the HTTP response body verbatim**, JSON braces and all; and **401 — the single most
> common failure these routes produce — is unclassified**, so the one error every household will
> actually meet renders "Something went wrong" behind a Retry that can never work, on the player too.

## Status

`✓ Built` — design-authored and built 2026-09-20 from R279's deferred finding. Not dev-reviewed.
Shipped in **v1.34**.

### Build (2026-09-20)

- **FR-R280-1** — `classifyStartFailure` and `FailureClass` moved verbatim to
  `ravilo-ui/.../components/LoadError.kt`; `PlayerErrorKind` → `LoadErrorKind`. One row added:
  `401 || 409 → REAUTH`, not retryable. `PlayerStore` keeps a one-line `classifyStartFailure`
  delegating to it, so R237's own call site reads unchanged.
- **FR-R280-2** — **fifteen** stores carry a kind, not thirteen: the sweep for the spec missed
  `SearchScreen`'s and `SeerrSearchStore`'s, whose `Error` states are constructed and **never
  rendered** — a different dead end, and out of this phase's render-site scope, but the same invented
  English (`"Error"`), so they were converted with the rest. `LiveTvGuideStore` used `getOrNull()`
  and threw the cause away before anyone could classify it; it now keeps the `Result`.
  `UpcomingDetailStore` raises its own `error("Not found")`, which carries no status, so it maps to
  `GONE` directly rather than through the classifier.
- **FR-R280-3** — one `LoadErrorState`. Three call sites needed a way out that did not exist:
  `UpcomingDetailStore` loaded in `init` with no retry (extracted to `retry()`), `SettingsStore.load`
  was `private`, and `DiscoverDetailScreen`/`UpcomingDetailScreen` took no `onBack` (added, wired
  from `RaviloApp`'s `pop()`).
- **FR-R280-5** — the kind → key mapping is `loadErrorTitleKey` / `loadErrorBodyKey` / 
  `loadErrorOffersRetry`, **pure and outside the composable**, so what an error screen says is
  covered by a test instead of by looking at a screen.
- **FR-R280-6** — `RAW_MESSAGE` in `check_ravilo_strings.py`. Proved by planting `Text(s.message)` in
  `ChannelScreen`: it fails, naming file and line.

**Two things the self-review caught after the first pass**, both the kind of defect this project's
history is made of:

- `DiscoverError` was left as the one site with no action at all. Its three states (Coming Soon,
  Request, the taxonomy walls) each own a store that can reload, so the dead end was avoidable
  rather than inherent. Fixed.
- **A D-pad regression of my own.** The first cut of `HomeErrorState` gave Sign out an `onUp` back to
  Retry and gave Retry no `onDown`, so the second action was reachable only by never leaving it. On a
  TV that is the whole interaction. `LoadErrorState` now owns **both** focus requesters and hands the
  caller each one, wiring the primary's Down to the secondary itself — so a site with a second action
  cannot forget half the chain.

**Tests: 587 green** (backend + client), including five new in `LoadErrorStringsTest` and one new in
`PlayerStartFailureClassificationTest` for the 401. `every_cause_has_a_real_sentence_in_every_language`
was **proved non-vacuous** by deleting `error.load.reauth.body` from `da.json` and watching it fail —
`t()` falls back to English per key, so an untranslated error screen is otherwise silent.

⚠ **One thing the spec claimed that was wrong:** it said ten render sites over thirteen stores. The
render sites are ten; the stores are fifteen.

## Context

### What the viewer actually sees

`TvApiClient.assertSuccess()` (`TvApiClient.kt:727`) throws `TvApiError.Http(status, bodyAsText(), …)`
for every non-2xx. `TvApiError.Http` declares `override val message: String` — so `.message` is **the
response body**, not the `"HTTP $status: …"` string the superclass is constructed with. Thirteen
stores then do `it.message ?: "Unknown error"` and put that in their `Error` state, and ten render
sites draw it.

A TV read route answers a dead device token with `call.respond(HttpStatusCode.Unauthorized,
mapOf("error" to "Not logged in"))` — nineteen sites in `TvRoutes.kt` do exactly this. So what a
Faroese household sees on their Home screen when a token expires is, literally:

```
{"error":"Not logged in"}
```

Not a bad translation. Raw JSON, rendered as prose, under a heading that says *Nakad gjekk skeivt*.

### Eight of the ten offer no way out

R207 established the rule for the detail screen — *"a failed load is never a dead end"* — and gave it
a focusable Retry. `HomeErrorState` has Retry + Sign out. **The other eight render sites are a bare
`Text`**: no Retry, no sign-in, no action of any kind.

| Render site | Draws | Action |
|---|---|---|
| `HomeScreen.kt:795` | `error.generic` + raw body | Retry · Sign out |
| `Shimmer.kt:172` `DetailErrorState` (movie + series) | `error.generic` + raw body | Retry |
| `ChannelScreen.kt:175` | raw body | — |
| `BrowseScreen.kt:253` | raw body | — |
| `SeededBrowseScreen.kt:436` | raw body | — |
| `SettingsScreen.kt:235` | raw body | — |
| `LiveTvGuideScreen.kt:187` | raw body | — |
| `UpcomingDetailScreen.kt:127` | raw body | — |
| `DiscoverDetailScreen.kt:76` | raw body | — |
| `DiscoverScreen.kt:257` `DiscoverError` (Upcoming · Discover · Taxonomy states) | raw body | — |

On a TV that matters more than on a phone: a phone has a system Back that always works, and several
of these screens are reached by a D-pad into a `Box` whose only focusable child never got drawn.

### 401 is unclassified — and that is a live defect, not a gap

`classifyStartFailure` (`PlayerStore.kt:62`) is R237's, and it is good code. Its ladder is:

```
409 → REAUTH · 403 → FORBIDDEN · 404 → GONE · 408/429 → UNREACHABLE
5xx → UNREACHABLE · 400..499 → GENERIC · else → UNREACHABLE
```

**401 falls through to `GENERIC`.** R237 was written against the playback route, where a re-pair is a
409, and nobody asked what the other routes answer. They answer 401, nineteen times over.

`GENERIC` renders `error.generic` — *"Something went wrong"* — with no body sentence, and
`showRetry = kind == UNREACHABLE || kind == GENERIC`, so it offers **Retry**. A dead token is
deterministic: that Retry is guaranteed to fail for as long as the viewer is willing to press it.
This is precisely the failure R237 exists to prevent, one status code to the left of where it looked.

There is no global 401 handling anywhere on the read path — `LoginScreen.kt:108` is the only place in
the app that reads `status == 401`, and that is the login form telling you your password was wrong.

### Thirteen stores, thirteen fallbacks

`"Unknown error"` (×9), `"Couldn't load channels"`, `"Channel not found"`, `"Failed to load
settings"`, `"Failed to restream"`, `"Not found"`. All English, none in any table, and each one a
sentence a store had to invent because its type demanded a `String`.

## Non-goals

- **The player's own sentences.** R237's wording is playback-specific on purpose (*"Sign in again on
  this TV to keep watching"*) and stays. This phase changes how the player *classifies*, never what it
  says once classified.
- **Retry policy inside the player's retry loop.** `FailureClass.retryable` / `retryAfterMs` drive
  R237's backoff and are not touched beyond the 401 row.
- **The 82 Danish / 83 Faroese accent-stripped words** R279 reported. Real, still open, still not a
  translation-architecture problem. Its own phase.
- **`DIRECT PLAY` / `HLS`.** R279 allow-listed it with the argument that R180 FR-RV-ASP1-2 says it
  should not be drawn at all. That argument is unchanged and this phase does not relitigate it.
- **Making the backend's error bodies translatable.** They are diagnostics for a log, and after this
  phase no viewer sees one. Translating them would be the wrong work done carefully.

## Functional requirements

### FR-R280-1 — one classifier, and it knows what 401 means

`classifyStartFailure` moves out of `PlayerStore.kt` into its own commonMain file as the app's single
classifier, and `PlayerErrorKind` is renamed **`LoadErrorKind`** — the five values are already generic
(`REAUTH`, `FORBIDDEN`, `GONE`, `UNREACHABLE`, `GENERIC`); only the name claimed otherwise. The
player keeps using it unchanged apart from the name.

One row is added, and it is the point of the phase:

```
401 → REAUTH, not retryable
```

401 and 409 both mean *this device's session no longer works; signing in again is the thing that
fixes it*. They differ in how the session died, which changes nothing the viewer is told or can do —
R237's own test for whether a kind deserves to exist.

`retryable` stays exactly as it is for every other status, so R237's backoff schedule is unchanged
for everything except a 401, which now stops instead of burning five attempts on a verdict.

### FR-R280-2 — a store carries a cause, never a sentence

Every `Error` state in the thirteen stores carries a `LoadErrorKind`. The thirteen invented English
fallbacks are deleted — not translated, deleted: a store has no language and must not choose words.

Stores in scope: `HomeStore`, `ChannelScreen`'s feed store, `BrowseScreen`'s store,
`SeededBrowseScreen`'s store, `DetailStore` (movie + series states), `SettingsScreen`'s store,
`LiveTvGuideStore`, `LiveTvPlayerStore`, `UpcomingScreen`'s store, `UpcomingDetailScreen`'s store,
`DiscoverStore`, `DiscoverDetailStore`, `TaxonomyStore`.

Two of them raise a failure rather than catching one — `UpcomingDetailScreen.kt:97`'s
`?: error("Not found")` and `LiveTvPlayerStore.kt:83`'s `"Channel not found"`. Both mean `GONE` and
say so directly, without inventing a throwable to classify.

### FR-R280-3 — one error surface, offering what the cause allows

A shared `LoadErrorState(kind, onRetry, onSignIn, onBack)` replaces all ten render sites, built from
R237's `PlayerSessionErrorOverlay` rules rather than a new set:

| Kind | Heading | Body | Actions |
|---|---|---|---|
| `REAUTH` | `error.load.reauth.title` | `error.load.reauth.body` | Sign in |
| `FORBIDDEN` | `error.load.forbidden.title` | `error.load.forbidden.body` | Back |
| `GONE` | `error.load.gone.title` | — | Back |
| `UNREACHABLE` | `error.play.unreachable.title` | `error.play.unreachable.body` | Retry |
| `GENERIC` | `error.generic` | — | Retry |

Retry appears **only** where trying again can plausibly change the answer — FR-R237-3's rule, which
is why 401 moving to `REAUTH` matters: it takes a useless Retry away and puts the action that
actually resolves it in its place.

Something focusable is always drawn, and it takes focus on appearance. A screen with a Retry focuses
Retry; a screen with neither Retry nor Sign in draws a focusable **Back**. This is R207's rule —
*a failed load is never a dead end* — finally applied to the eight screens that never got it.

`HomeErrorState` keeps its Sign out as a second action: Home is the screen a viewer lands on with a
dead token, and R237 already treats signing out as Home's own case. `DetailErrorState`'s signature
changes to take a kind; its callers are two lines.

### FR-R280-4 — the raw text survives, in the log, never on the screen

Classifying must not throw the diagnosis away. Each store keeps the raw `message` on its `Error`
state for logging, and **nothing renders it**. The value of `{"error":"Not logged in"}` is real — it
is just worth exactly one log line and zero pixels.

### FR-R280-5 — the strings, in all three languages

New keys, because the player's existing bodies are playback-specific:

| Key | en |
|---|---|
| `error.load.reauth.title` | Sign in again |
| `error.load.reauth.body` | This device's session has ended. Sign in again to carry on. |
| `error.load.forbidden.title` | Not available on this profile |
| `error.load.forbidden.body` | This profile can't see this. |
| `error.load.gone.title` | This isn't here any more |

`error.generic` and the two `error.play.unreachable.*` keys are reused as they are: *"Couldn't reach
the server" / "Check the connection and try again."* is already exactly right for a failed screen
load, and a second key saying the same thing is the drift R279 spent a phase removing.

Danish and Faroese are drafts by the R279 rule — the shipped table wins wherever a string exists.
They must be written without the accent-stripping R279 reported: `sambandið`, not `sambandid`.

### FR-R280-6 — the check catches a store's message reaching a screen

`scripts/check-ravilo-strings.sh` reads text-painting positions and fails on a **literal**. It cannot
see `Text(s.message)`, because that is not a literal — which is why thirteen stores drifted into this
shape without anything noticing.

The check gains one rule: in a text-painting position, an expression whose root is a `.message`
property fails, naming the file and line. That is narrow enough to have no judgment in it, and it is
the exact shape all ten sites share.

## Acceptance

1. A device whose token has been revoked opens Home: the screen reads *Sign in again* with a body
   sentence and a focusable **Sign in**. No JSON, no `Unknown error`, no Retry.
2. The same device in Danish reads Danish; in Faroese, Faroese.
3. `grep -rn 'Text(s\.message' ravilo-ui/` finds nothing.
4. `grep -rn '"Unknown error"' ravilo-ui/` finds nothing.
5. With the backend stopped: every one of the ten screens draws *Couldn't reach the server* and a
   focusable Retry, and Retry works once the backend is back.
6. A title deleted from the library, opened from a stale Home tile, reads *This isn't here any more*
   with a focusable Back — not a spinner and not a raw 404 body.
7. The player is unchanged for 403/404/409/5xx/no-response: same sentences, same actions, and
   `PlayerStartFailureClassificationTest` passes with only the `PlayerErrorKind` → `LoadErrorKind`
   rename applied.
8. The player given a 401 shows the re-pair path, not *Something went wrong* with a Retry.
9. Planting `Text(s.message)` in any screen fails `scripts/check-ravilo-strings.sh`, and the failure
   names the file and line.
10. Every screen in the FR-R280-3 table has something focusable when it errors, and focus lands on it
    without a D-pad press.

## Found and deliberately not fixed

**A failed search says "0 results".** `SearchState.Error` and `SeerrSearchState.Error` are the two
`Error` states in the app that are **constructed and never rendered** — no screen has a branch for
them. Both screens derive their item list with `(state as? …Loaded)?.results?.items.orEmpty()`, so a
search that failed produces an empty list, and the label falls through to
`str("search.results", count = 0)` — or, on the Seerr screen, sits one condition away from
*"No results for X"*.

That is worse than an untranslated sentence: it is a confident, translated, **false** statement. A
viewer whose server is unreachable is told their library does not contain the thing they are looking
for.

Both states carry a `LoadErrorKind` after this phase, so the data is there. It is not fixed here
because it is not the same decision: every other screen in R280 had a failure surface that was
merely wrong, and these two have none at all — what a failed search should show (the error surface?
the previous results with a quiet note? the suggestions?) is a design question this phase did not
ask. Its own phase.

## Open questions

1. **Should a 401 sign the viewer out by itself**, rather than offering the action? It would be one
   place — the classifier already sees every 401 in the app. Lean **no, not in this phase**: an
   automatic sign-out on a transient server misconfiguration would drop a household's TV to the login
   screen with no way to tell them why, and the same 401 the backend emits for a dead token is also
   what it emits when Jellyfin itself is unreachable at the wrong moment. Offering the action is
   honest and reversible; taking it is neither. Worth revisiting once 401 is distinguishable.
2. **`LiveTvPlayerStore`'s `Error` is not one of the ten render sites** — the live player draws its
   own. It is in FR-R280-2's list because its `"Channel not found"` is the same invented English, but
   whether its screen adopts `LoadErrorState` or keeps its own presentation is a real choice, and this
   spec does not make it.
