# Phase R64 — Rename "Channels & Collections" → "Channels" everywhere (FR-RV-RN1)

## Problem
The feature is labelled **"Channels & Collections"** in some places and just **"Channels"** in others.
There is no separate "Collection" concept in the data model — it's purely a label. Standardize on
**"Channels"** across both the Ravilo TV app and the Jellystructure admin, in all locales.

## Findings (exhaustive label audit)
There is **no `Collection`/`CollectionConfig` type** — "collection" is display-only, so this is a safe
string rename with **no data migration**. The model identifiers (`ChannelConfig`, `Channel`,
`ChannelStyle`, `ChannelButtonPadding`, the `channels` field, `data-kind="channel"`, `ch-` ids/CSS) all
already say **channel** and **must not change** (renaming them would break stored config / wiring).

The actual occurrences of the "& Collections" wording:

**Ravilo TV app — i18n key `section.channels`** (`ravilo-ui/.../ui/i18n/Strings.kt`):
- EN `:28` `"Channels & Collections"` → `"Channels"`
- DA `:103` `"Kanaler og samlinger"` → `"Kanaler"`
- FO `:177` `"Kanalar & samlingar"` → `"Kanalar"`
(Leave `section.channels_sub` / subtitle keys — "Browse by category" etc. — unchanged.)

**Jellystructure admin** (`src/wasmJsMain/.../ui/RaviloConfig.kt`):
- `:896` section header `"Channels &amp; collections"` → `"Channels"`
(The nav button `:223` already says "Channels"; `+ Add channel`, "Channel button", etc. already
say "channel" — keep.)

**Design mockups** (visual source of truth — update so the next sync doesn't reintroduce the old label):
- `design/app/ravilo-config.html:120` `<h3>Channels &amp; collections</h3>` → `Channels`
- `design/app/ravilo-createflows.jsx:176` Section title `"Channels & collections"` → `"Channels"`;
  `:457` `"Create a filter · Channels & Content rows"` (already fine — keep)
- `design/ravilo/ravilo-i18n.js` key `channels`: EN `:15` `'Channels & Collections'` → `'Channels'`,
  DA `:36` `'Kanaler & samlinger'` → `'Kanaler'`, FO `:57` `'Råsir & savn'` → `'Råsir'`

**Comment cleanup (optional):** `ravilo-ui/.../components/AppBar.kt:205` comment "channel/collection
pages" → "channel pages".

## Goal
Every user-facing surface says **"Channels"** (and the locale equivalent), in both apps and the design
mockups. No model/identifier/serialization change.

## Scope
- `ravilo-ui/.../i18n/Strings.kt` — `section.channels` value in en/da/fo.
- `src/wasmJsMain/.../ui/RaviloConfig.kt` — the one section header at `:896`.
- `design/app/ravilo-config.html`, `design/app/ravilo-createflows.jsx`, `design/ravilo/ravilo-i18n.js`
  — so the design export carries the new label (else the sync reverts it).
- (optional) the `AppBar.kt:205` comment.

## Non-goals
- **No identifier/model renames** — `ChannelConfig`, `channels`, `ChannelStyle`, `data-kind="channel"`,
  CSS/ids stay exactly as they are (renaming them is a breaking data/wiring change for a label-only fix).
- No behaviour change; channels still work the same (this also covers Collections-as-channels — there's
  no second concept to remove).

## Acceptance
- The TV home section header and the admin config section both read **"Channels"** (localized), and a
  grep for "& Collections" / "og samlinger" / "& samlingar" across `ravilo-ui`, `src/wasmJsMain`, and
  `design/` returns nothing.
