# Phase R318 — A row kind the app does not know never breaks Home, and *Recommended* is an ordinary row

> The client half of Phase 269 (the *Recommended* row), 2026-09-26.

## Status

`Planned` — written 2026-09-26, **dev-reviewed 2026-09-26** against `main` `0e5e434f` (see *Dev review*
at the end). Client (`ravilo-ui`, `shared`'s DTOs): TV, phone, web. **Numbering:** verified against
`STATUS.md` the same day — Ravilo taken through **R317**.

## Why the client needs a phase at all

Phase 269's row needs **nothing** new from the app to be shown: the server sends it as an ordinary content
row (269 FR-269-2). What 269 found on the way is the reason for this phase. Every server-sent enum is
decoded strictly:

- `Row.kind` / `RowConfig.kind` are `RowKind`;
- also `TileShape`, `Skin`, `ChannelStyle`, `MatchMode`, `SkipMode`, `UiDensity`.

The client's `Json` is `ignoreUnknownKeys = true, isLenient = true`, with no enum fallback. So **one new
enum value from a newer server fails the whole payload**: Home, a channel, or `/api/tv/config` (which
carries the skin, the language, every setting) stops loading on every installed app. 269 works around it
server-side for this one kind. This phase makes the next new value harmless.

## Requirements

**FR-R318-1 — An unknown enum value falls back; it never fails the payload.** The client's `Json` gains
`coerceInputValues = true`, and every server-sent enum field gets a default to fall back to:

| Field | Default |
|---|---|
| `Row.kind`, `RowConfig.kind` (no default today) | `CUSTOM`, a plain content row |
| `Channel.style` (no default today) | `TEXT`, as `ChannelConfig.style` already defaults |
| `tile_shape` | `POSTER` (already the default) |
| `default_skin` / `viewer_skin_override` | `AURORA` / `null` (already the defaults) |
| `match` | `ALL` (already the default) |
| `skip_intro` / `skip_credits` | `PROMPT` (already the default) |
| `ui_density` | `COMFORTABLE` (already the default) |

A field that has no sensible default is made nullable and treated as absent. Enums inside lists (a list
of kinds) must not fail the list either: the element is dropped. That needs a lenient serializer, since
`coerceInputValues` covers only fields with a default.

**FR-R318-2 — `RECOMMENDED` is known, and drawn like any content row.** `RowKind` gains `RECOMMENDED`, so an
app with this phase would read it even if a later server sent it as it is. It is drawn exactly like a
`CUSTOM` row: the same tiles and focus behaviour (R240's focus detail applies as on any Home row), and
the title the server sends (the admin's text, 269 FR-269-1). No new string: a row's title is the
admin's, like every other row.

**FR-R318-2b — *See all* opens the viewer's whole list.** Owner, 2026-09-26: the row shows its 20 or 30,
*"and then a view all like the other rows."* The server marks the row with an additive
`recommendations: true` (269 FR-269-2 still sends it as `CUSTOM`, so an installed app draws it with no
*See all*, as today). An app with this phase shows *See all* on such a row when it has more titles than
the row shows. It opens the ordinary browse page, backed by a new
`GET /api/tv/recommendations` that answers the viewer's still-eligible list (up to 50) as `BrowseCard`s,
in rank order. The page opens in **that order**. Its sort control offers it first, labelled with the
page's own title (the row's title, the admin's text), so it needs no new string, and the page's other
sorts and filters work on the 50 as on any browse page.

**FR-R318-3 — Tests.** Decode, with the app's own `Json`:

- a Home feed whose row has `kind: "SOMETHING_NEW"` → a `CUSTOM` row, the rest of the feed intact;
- a config with `skin: "SOMETHING_NEW"` → the default skin, every other field intact;
- a list with one unknown element → the rest of the list.

A `recommendations: true` row with more titles than it shows renders a *See all* tile, and the page it
opens lists the recommendations in rank order.

## Non-goals

- The row's content and order: Phase 269 (and 270).
- **Observed, not changed:** the system rows' default titles are English literals sent by the server
  (`HomeFeedService`: `"Continue Watching"`), while `i18n` has `row.continue` / `row.new_all` in all
  three languages and no client code reads them. A Faroese household sees *Continue Watching* unless the
  admin retitled the row. Worth its own phase: resolve a system row's default title from the viewer's
  language.

## Acceptance

1. A backend test build sends one row with an unknown kind: Home loads on the Pixel 9 and the TV, and the
   row shows as a plain row.
2. With Phase 269 live, the *Recommended* row shows its 20 (the row's limit) and a *See all* that opens
   all 50 in the recommended order, on the TV, the phone and the web. An app from before this phase
   shows the same row with no *See all*.
3. FR-R318-3's decode tests pass.

## Dev review (2026-09-26, against `main` `0e5e434f`)

1. **Several decoders, one fix.** Server payloads are decoded through several `Json` instances:
   - `TvApiClient`'s own (`TvApiClient.kt:42`, used with `decodeFromString`, e.g. `getConfig` `:417-421`);
   - the content-negotiation clients' (`androidMain/.../RaviloRootActuals.kt:42`,
     `wasmJsMain/.../RaviloRootActuals.kt:40`);
   - the Home snapshot cache's (`HomeSnapshotCache.kt:35`), which reads a stored `HomeFeed` back;
   - and the cast, screen and resume decoders (`Cast.kt:97`, `ScreenSender.kt:130`,
     `PlayerResume.kt:73`, `CastSenderAndroid.kt:91`).

   Define one `RaviloWireJson` in `shared` with `ignoreUnknownKeys`, `isLenient` and
   `coerceInputValues`, and use it at every site that decodes server data. A single missed site is where
   the next new value would break something.
2. **Defaults.** `Row.kind` (`Models.kt:418`) and `Channel.style` (`:408`) have none today; they get
   `CUSTOM` and `TEXT`. Every other server-sent enum already has a default (`Models.kt:450`, `:900`,
   `:908`, `:1009-1021`), so `coerceInputValues` covers them as soon as it is on.
3. **Enums in lists.** None are server-sent today: `List<RowKind>` or similar does not occur in the
   payloads. Keep FR-R318-1's list rule as a requirement for the next such field, and test it with a
   small lenient serializer on a test-only type rather than inventing a production field.
4. **The *See all* rule** is `HomeScreen.kt:472` (`row.kind == CONTINUE || seedQuery != null ||
   seedMediaKind != null`). It gains `|| row.recommendations`. The page is `SeededBrowseScreen` with a
   store whose fetch is `GET /api/tv/recommendations` instead of `browseByQuery`, the same way the
   Library tab builds its own store (`RaviloApp.kt:1200-1202`); `SortField` gains `SOURCE` (the server's
   order), labelled with the page title.
5. **Ship it early.** It protects every later enum addition, so it should reach the stores before 269.

**Net effect.** One shared `Json`, about eight call sites pointed at it, two defaults, one enum value,
tests.
