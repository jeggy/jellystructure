# Design brief — Movies we don't have yet: suggestions from what the household watched, with Download / No thanks

**Date:** 2026-09-27 · **For:** the design project (Cosmos) that owns `design/app/` and `design/ravilo/` ·
**Status:** brief. The method is proven on production data (two trial rounds the same day, §1); the
placement, the actions and the Ravilo row are the owner's ask (§2–§3); the questions in §6 carry a lean and
can be drawn as marked options. No spec number yet — the next free admin number is taken when the round-1
directions come back.

> Owner, 2026-09-27: *"Extend the AI support in jellystructure: a suggested list of movies that we currently
> do not have, but are available on Seerr, based on all users and what they have watched. Show it somewhere in
> jellystructure (only if Seerr is connected), and add the result as a row in the Request part of the Ravilo
> config. Click an item and say **Download** — it starts downloading through Seerr/Radarr. Or click **No thanks**
> (or some other button text) and pick a reason: not interested · already seen it · too old · other."*
> And after round one: *"look at the amount of stuff — one viewer has seen a lot of horror / thriller"* — the
> list must follow what the household **actually watches in volume**, not what the recommendation graph is
> densest in. Round two returned 20 items sized that way and was accepted as the shape.

This is **not** Phase 269. 269's *Recommended* row orders titles the viewer **already has**; this brief is
its sibling for titles the household **does not have** — same signals, opposite exclusion, and a
consequence (a download) that 269 never has.

## 0. What exists, so nothing is drawn twice

| Have | Where | Reuse |
|---|---|---|
| Per-viewer taste signals from Jellyfin — played, in progress, favourites, rewatches, 120-day decay, weak negative for abandoned starts | Phase 269 `RecommendationEngine` (FR-269-4), built in the background as a pipeline step, *Rebuild now*, per-viewer *why* | The **same signals feed this list**. Only the candidate pool differs: Seerr's catalogue minus the library |
| The Seerr connection, its card and the *Seerr connected* state | `app/settings.html` → Download tools; Phase 136 | The gate. Not connected ⇒ this surface is **absent** (218's rule: absent, never greyed) |
| Request feeds in Ravilo config — a per-viewer list of Seerr *discover* rows (popular · genre · language · studio · upcoming · trending), show/hide, reorder | `app/ravilo-config.html` → Request section; Phase 137 (`SeerrFeed`, `SeerrDiscoverEndpoint`) | The new row is **one more feed in that list** (§3). Same card, same toggles |
| The viewer's Request tab on the TV and phone, request detail, language pick, *In progress* / *Requested* / *Available* states | `ravilo/Ravilo TV.html` Discover ▸ Request, `Ravilo Mobile.html`; R171 · R190 · 139 · 186 | The row renders like any other Request feed; tapping a tile opens the **existing** request detail |
| Requesting through jellystructure into Seerr, attributed to the real Jellyfin user, and the acquisition record that follows it (requested → approved → downloading → available; dead requests retired) | `SeerrDiscoverService.request()`, Phase 156, Phase 186 | **Download = this call.** The status that comes back is the status the list shows afterwards |
| Library ↔ Seerr exclusion: what we own by TMDB id, what is already requested, what Seerr says is available/processing | `SeerrDiscoverService` (`libraryByTmdbId`, `mediaInfo.status`), 137 | The filter that makes "we don't have it" true |
| AI work as a queue, Activity ▸ Jobs & workers card, monthly limit, last-5 conversations | Phase 272 | Optional final ordering of the list (as 269's `ai_order`), **not** the list itself — §1 needs no model call |
| Seerr's own **blacklist** (`/api/v1/blacklist`, empty on this installation) | Seerr 3.4 | A possible destination for *No thanks* (§6 Q4) |

Rules carried over: **absent, never greyed**; **render-never-compute** (the server builds the list, the admin
and Ravilo show it); **no product names in viewer copy** (Ravilo says *Request*, never Seerr/Radarr — the
admin page may name Seerr, since the admin configured it); 46 px targets / 13 px floor on the phone; Noir
drops accent tints; **no real library titles in the mockups** (fictional stand-ins, as everywhere).

---

## 1. How the list is made — and what the two trial rounds taught

Method, in one paragraph, for the copy on the page (§2.4 needs to say this in fewer words):

1. **Sources.** Every movie a viewer finished or has in progress (Jellyfin per user, test accounts excluded).
   A finish counts 1, an in-progress 0.5; a title watched in the last 60 days counts 1.5×, older than
   180 days 0.7×. Series count toward taste (genre share) but cannot be sources for movie suggestions.
2. **Candidates.** For each source, Seerr's *recommendations* and *similar* lists (TMDB's graph, through the
   Seerr the household already runs — no new external service).
3. **Exclude** anything in the library (TMDB id), anything already requested in Seerr, anything Seerr marks
   available or processing, anything the household has already dismissed (§2.3).
4. **Score** each candidate by the weight of the sources pointing at it, ×1.25 per additional viewer who
   reached it, a mild bonus for rating and for a 2025+ release. Quality floor: ≥ 6.3 and ≥ 400 votes
   (≥ 100 for a 2025+ release, which has had no time to collect votes).
5. **Size the buckets by volume.** Count the household's *finished* movies per cluster (horror ·
   family/animation · thriller/crime · sci-fi/action · drama · comedy) and give the 20 slots out in that
   proportion. On production that was 32 · 27 · 14 · 10 · 9 · 8 % → **7 · 5 · 3 · 2 · 2 · 1**. Without this step
   round one gave family films 15 of 20 slots, because the animation graph is far denser than the horror
   graph — the owner's correction.
6. **One per franchise**, and when the library owns nothing of a franchise, offer its **first** film even if
   the graph pointed at a sequel (the graph points at *Part 3* because the household watched something like
   *Part 3*; a household with none of them should start at *Part 1*).

What production data showed, and the design has to carry:

- **The history is one viewer deep.** Of 112 finished movies, one viewer accounts for ~90 %; three viewers
  have zero. "Based on all users" is, today, one person's list with two others nudging it toward kids'
  films. The page must say **whose** watching produced a suggestion (§2.2), and the per-viewer version on
  Ravilo (§3) must not pretend a viewer with no history has one (empty state, not someone else's list).
- **A candidate reached from several viewers is the strongest signal there is** — only a handful were, and
  every one of them was a good pick. Show that (§2.2).
- **Recent releases have almost no graph.** A viewer whose horror is mostly this year's films gets a list that
  resolves to the 1973–2013 canon those films reference — correct, but not "new horror". *New in <genre>* is
  a different signal (Seerr discover by genre + release window, filtered by the same taste) and belongs in a
  **separate row**, not mixed in. Draw nothing for it now; note it.
- **Thin buckets are thin.** Drama's best candidate scored a fifth of horror's. The page should show the
  bucket's strength honestly (a count of sources, not a percentage bar that invents precision).
- **Series taste is unused for movie suggestions.** A viewer who has only watched kids' series has no
  sources. Open question Q6.

---

## 2. The admin surface (jellystructure)

### 2.1 Where
**Recommendation: a page of its own in the sidebar's Ravilo group — *Suggestions*** (working title; also
*Not in the library yet*), the way Live TV got its own page in Phase 147 — plus **one summary card on the
Dashboard** (*"14 suggestions waiting · 3 new since Tuesday"* → the page). Rejected: a tab inside Library
(that page is about what we own; putting what we don't own beside it invites confusion with the file-health
chips) and a card inside Settings → Download tools (a list that changes weekly is not a setting).

**Gate:** Seerr not connected or disabled ⇒ **no sidebar entry, no card, no route** — nothing to grey out.
Seerr connected but unreachable right now ⇒ the page renders the **last built list** with a banner (*"Built
Tuesday 06:30 · Seerr couldn't be reached since 09:12"*), and Download is disabled with the reason inline
(the one place *disabled* is allowed, because the list itself is still true).

### 2.2 The list
- **Header:** built-when · *Rebuild now* (queues; it does not run inline, per 213/272's discipline) · how
  many shown of how many built (20 of 50) · a *Dismissed (n)* link (§2.3).
- **Grouped by bucket, in volume order**, each group headed by its share and its evidence: *"Horror · 7 —
  the household finished 36 horror films"*. A group with no candidates is **absent**, not empty.
- **Tile / row** (the page should work as a poster grid on wide screens and rows on a phone — `wf.css`
  has both): poster · title · year · rating · runtime · one line of synopsis · **the because line** — *"because
  Eydun finished ⟨title⟩ and ⟨title⟩; Sanna is watching ⟨title⟩"* — naming the viewers (the admin sees
  per-viewer devices already; the point of the page is to know who this is for) and up to three source
  titles, newest first. A tile several viewers reached gets a quiet **"2 viewers"** chip; that is the
  strongest signal the page has and should read as such.
- **Franchise note**, when §1.6 applied: *"First of a series you don't have — the match was ⟨sequel⟩."*
- **Seerr state chip** after an action: *Requested* → *Approved* → *Downloading* → *In the library* (then the
  tile leaves the list on the next build, and *In the library* is the last thing it says). Those are the
  acquisition states 137/186 already render on the TV's Request tab — same words.
- **Age rating** where Seerr has one (it does — the certification came back for most candidates in the
  trial), because a kids-heavy household will want to see *PG* beside an animated film.

### 2.3 The two actions
Both live on the tile; both are one tap, then one confirmation at most.

**Download.** Sends the request through the existing path (`SeerrDiscoverService.request`). What Seerr
does next — auto-approve or wait for approval, Radarr's quality profile, the language — is Seerr's and
139's, unchanged. The tile shows the returned state; no spinner longer than the round trip. If Seerr refuses
(already requested by someone since the build; not found), the tile says so and stays.
Open: **whose request is it** (Q1) and whether the button says *Download* or *Request* (Q2).

**No thanks.** Opens a small reason picker on the tile — not a modal — with four reasons:
**Not interested · Already seen it · Too old · Other** (Other reveals one optional text field). Picking one
removes the tile with a short undo toast (*"Dismissed · Undo"*, ~6 s), and the title never comes back
from any source. Reasons are not decoration; each does something to the next build:

| Reason | Effect on the next build |
|---|---|
| Not interested | The title is out. The edges that produced it (source → this) lose weight, so a source that keeps producing dismissed titles fades. |
| Already seen it | The title is out **and counts as watched** for taste — it becomes a *source* (weight 0.7, as an old finish). This is the one reason that *adds* information. |
| Too old | The title is out, and the household's **release-year preference** tightens by a step (an honest control the page otherwise doesn't have). |
| Other | The title is out; the text is kept for the *Dismissed* view and nothing else. |

**Dismissed view:** the list of everything said no to, with reason, who, when, and *Bring back*. This is the
undo beyond the toast; it also makes the reasons auditable, so a household member can see why something
they wanted never shows.

Text for the button: *No thanks* is the owner's own phrase and reads right beside *Download*. Alternatives
considered: *Skip* (implies later), *Hide* (implies it still exists somewhere), *Not for us* (good, slightly
long). Lean: **No thanks.**

### 2.4 Empty and first-run states
- **No history yet** (a fresh installation): *"Suggestions appear once someone has finished a few films."*
  Nothing else on the page.
- **Everything dismissed or requested:** *"Nothing new right now · next build Tuesday 06:30"* with *Rebuild now*.
- **Seerr connected, first build not run yet:** the header with *Build now* as the primary action.

### 2.5 Build cadence and the pipeline
A pipeline step beside 269's `build_recommendations` (weekly by default is enough — the sources change
slowly and the graph changes slower), plus a rebuild when Seerr's connection is first saved. It is **not**
AI work: no model call is needed to build the list. If an AI pass is ever added, it is 272's queue ordering
the 20 (as `ai_order` does for 269), never choosing them, and it shows in Activity ▸ AI like any other job.

---

## 3. The row in Ravilo (the viewer side)

### 3.1 In the config editor (`app/ravilo-config.html` → Request)
One new entry in the **Add row** catalogue of Phase 137's feed list: ***Suggested for you — films we don't
have yet***. It behaves like every other Request feed (name editable, show/hide, drag order, per-viewer scope
through the existing Global / per-user switcher), with two differences the editor states in its description:
- it has **no endpoint parameter** (no genre/studio picker — the viewer's own history is the parameter);
- its **preview is schematic** — the editor cannot show the real list per viewer (the same limitation 269
  recorded for its editor preview: *Preview as {viewer}* is not built).

Lean on scope: **per viewer** on Ravilo (the taste signals are per viewer, and a kids profile must get a
kids list — see 3.3), **household** on the admin page (§2), where the *because* line says whose it is.

### 3.2 On the TV and the phone
The feed renders in Discover ▸ Request exactly like *Popular* or *Trending*: the same tiles, the same request
detail on Select, the same *Request* button gated on `can_request`, the same states afterwards. **Nothing new
is drawn on the viewer side**; that is the point of making it a feed. Two things are deliberately **not** on
the TV:
- **No *No thanks*.** A TV is not a moderation surface, and a reason picker is settings-shaped. If the owner
  wants a viewer-side dismissal it is a *later* round; it would be *Not for me* with no reasons, and it must
  only affect that viewer's row.
- **No *because* line.** R240's focus detail and 269's *why* already cover how much a TV says about its
  reasoning; the request detail's synopsis is enough here.

### 3.3 Kids profiles
A kids profile's row is built against the same candidate pool but filtered by the age gate the Home feed
uses, using the certification Seerr returns; a candidate with no certification is **not shown to a kids
profile** (155's rule: unrated is treated as 18, never badged). The admin page shows the certification so the
operator can see what the gate is doing.

### 3.4 Wire rule
Installed apps must keep working. The feed reaches an app as an ordinary Request row (the apps receive
resolved rows, not endpoint kinds); whatever new enum value the editor needs must not appear in anything an
installed app decodes without a default (R318 gives new apps tolerance; the apps already on the TVs have
none). Dev's call on the shape; the design constraint is only that the viewer side needs **no new screen**.

---

## 4. Strings (en drafts · da · fo to follow; the shipped table wins where a key exists)

Admin (`app/`): *Suggestions* · *Not in the library yet* · *Built {when}* · *Rebuild now* · *Build now* ·
*{n} of {m}* · *Dismissed ({n})* · *Bring back* · *because {viewer} finished {title}* · *{viewer} is watching
{title}* · *{n} viewers* · *First of a series you don't have — the match was {title}* · *Download* ·
*Requested* · *Approved* · *Downloading* · *In the library* · *No thanks* · *Not interested* · *Already seen
it* · *Too old* · *Other* · *Dismissed · Undo* · *Suggestions appear once someone has finished a few films.* ·
*Nothing new right now · next build {when}* · *the household finished {n} {genre} films* · *Seerr couldn't be
reached since {time}*.

Ravilo (`ravilo-i18n.js` / `i18n/*.json`): **one** new key — the feed's default title, *Suggested for you*
(fo/da per R288's lexicon; a film is *filmur*, never a product name).

---

## 5. What not to draw
- A per-viewer preference screen (genre sliders, "more like this"). The reasons in §2.3 are the only controls.
- Series suggestions. Different graph, different exclusion (a partially-owned series is *have* and *don't
  have* at once); a later round.
- A *New in {genre}* row (§1) — separate signal, separate brief.
- Anything on the TV beyond the feed (§3.2).
- Any copy naming TMDB, Radarr or Seerr on a viewer surface.

## 6. Open questions — each with a lean, drawable as marked options
1. **Whose request is a Download from the admin page?** (a) the admin's own Seerr user — honest, it *was* the
   admin who clicked; (b) the viewer the *because* line names — Seerr then shows them as the requester and
   they get Seerr's notifications. **Lean (a)**, with the viewer named in the request's note if Seerr keeps one;
   (b) puts words in a viewer's mouth.
2. **Button text: *Download* or *Request*?** Seerr may still require approval, and 139's language steering
   runs after. *Download* is what the owner said and what the household will experience when auto-approve
   is on. **Lean: *Download***, with the state chip saying *Requested* / *Approved* when approval is pending,
   so the word is never a lie for long.
3. **List size:** 20 shown (owner's round-2 number) of 50 built, or all 50 with the 20 above a fold?
   **Lean: 20 shown, *Show more* to 50** — the buckets stay readable at 20.
4. **Should *No thanks* also write to Seerr's blacklist**, so the title vanishes from Seerr's own Discover and
   from every Request feed on the TVs? **Lean: no by default** — the blacklist is household-wide and
   permanent in another product; a *Not interested* on the admin page should not remove a title a viewer
   might request themselves. Offer it as a checkbox on *Other*? No — keep it a Settings-level switch if ever.
5. **Viewer names on the *because* line, or "someone in the household"?** **Lean: names.** The admin already
   sees per-viewer devices, history and requests; anonymising here would be the odd one out.
6. **Series as taste for movie suggestions** (a viewer with only series history has no sources today).
   **Lean: yes, genre-share only** — series weight the buckets, never produce candidates.
7. **Bucket clusters** — the six in §1.5 are the trial's; should the page derive them from 271's genre
   catalogue instead of a fixed six? **Lean: fixed six for the round-1 drawing**, derive later.

## 7. For the round-1 drawing
- `app/suggestions.html` (new; sidebar Ravilo group; Dashboard card in `app/index.html`): the list with two
  buckets full and one thin, one tile in each post-action state, the reason picker open on one tile, the
  Dismissed view, the three empty states, the Seerr-unreachable banner. Light + Dark.
- `app/ravilo-config.html` → Request: the new entry in the Add-row catalogue and its card in the feed list
  with the schematic-preview note.
- `ravilo/Ravilo TV.html` + `Ravilo Mobile.html`: the feed in Discover ▸ Request — **confirm nothing new is
  needed** rather than draw; one frame each is enough.
- Q1–Q5 as marked options on the canvas.

## 8. Added 2026-09-28 — Download asks first (owner decision; amends FR-274-10's "one tap")

> Owner, 2026-09-28, on the built page: *"there is a download button, which I think is way too fast to just send
> off the request. We want a popup … the popup acts as a confirm popup and also asks what quality it should
> download it in; its available options should come from Seerr itself."*

**What changes.** *Download* on a tile (and on the Dashboard card's tiles, if drawn there) no longer sends the
request. It opens a **confirm sheet** for that one film; the request goes only from the sheet's own button. The
tile's state chain after that (*Requested → Approved → Downloading · {n}% → ✓ In the library*) is unchanged.

**What the sheet holds**, top to bottom:

1. Poster, title, year, the *because* line (so the admin sees why it was suggested), the age rating.
2. **Quality** — a choice list of the Radarr **quality profiles as Seerr reports them** for the server the request
   would go to: Seerr's `GET /service/radarr` (the servers: name, `is4k`, `isDefault`, `activeProfileId`) and
   `GET /service/radarr/{id}` (`profiles[] {id, name}`, `rootFolders[] {path, freeSpace}`). Pre-selected: the
   server's active (default) profile, marked *Seerr's default*. The names are Radarr's own (*HD-1080p*,
   *Ultra-HD*, *Any*) — never invented, never translated.
3. **Server**, only when Seerr has more than one Radarr (a 4K server beside the HD one): a second choice, which
   re-lists the profiles for the chosen server. One server ⇒ no control, the sheet says *to {server name}* as a
   fact. This is where a *4K* pick lives — it is a server in Seerr, not a quality.
4. **Folder**, folded under *More* with its free space, pre-selected to the server's active folder. Lean: keep
   it out of round 1 unless the household actually has two folders (§8 Q3).
5. The request's note as it will be sent: *Suggested for {viewer}* (FR-274-10), read-only.
6. **Cancel** · **Download in {profile}** (primary). While the options load, the primary reads *Asking Seerr…*
   and is disabled; if Seerr cannot be asked, the sheet says so in one line and the primary is disabled with
   the same reason the page's banner uses. The choice is sent as `profileId` (+ `serverId` when chosen, `is4k`
   when the chosen server is the 4K one) on `POST /request` — fields Seerr already accepts, and `profileId` the
   backend's Seerr client already sends for 139's language steering, so the wire change is small.

**Rules.**
- The list is **Seerr's list**, fetched when the sheet opens (cached for the page's life), never a jellystructure
  setting. If Seerr says the admin's user may not choose (no advanced-request permission), the sheet shows the
  default as a fact and explains in one line; the request still goes.
- **No remembered choice** across films in round 1 (§8 Q1): every sheet opens on Seerr's default, because the
  default is Seerr's own decision and the admin should see it each time.
- Escape / Cancel / tapping outside sends nothing; the tile is untouched.
- The same sheet is the confirm for *Download* wherever 274 draws it; there is no second, quicker path.
- Nothing about this reaches Ravilo: the viewer's request row (R320) keeps Seerr's defaults, as R171 does.

**States to draw.** Loading options · one server with three profiles · two servers (HD + 4K) with the profile
list swapping · Seerr unreachable · admin may not choose (default shown as a fact) · request refused by Seerr
after confirm (the tile's one-line refusal, FR-274-10) · a single profile only (the choice reads as a fact).

**Questions (round 1, leans).**
| # | Question | Lean |
|---|---|---|
| Q1 | Remember the last chosen profile for the next sheet | **No** in round 1; Seerr's default every time |
| Q2 | Sheet or dialog | **Sheet** on narrow, centred dialog on wide — the segment editor's confirmation idiom (260) |
| Q3 | Show the folder chooser | **Only when the server has two or more folders**; otherwise nothing |
| Q4 | Show the free space beside a folder | **Yes**, as Seerr reports it — it is the one fact that changes the choice |
| Q5 | Should *No thanks* get a confirm too | **No** — it has Undo (6 s) and Bring back; a confirm on both would make the page slow to use |

**Spec home:** an amendment to 274 (FR-274-10a), not a new phase — the backend gains two read-throughs of Seerr's
service endpoints and three optional fields on the download route.
