# Auto-fetch turns a broadcaster's series page into a paced, scheduled download

Task: DEB-2474

> Owner, 2026-10-14 (DEB-1076): round 2 of the design studio, *"We just got some new AI designs in the design
> tool"*. Its first feature is Auto-fetch: paste a series page and a TMDB id, the model works out how the page serves
> its videos, confirms it is a series, maps its episode titles to seasons and episodes, and stores a fetch that re-runs
> on a schedule.
>
> Owner, 2026-10-25 (DEB-799, comment `232bfa9e`): *"Yes"* to Tróndur's recommendation: investigate and spec Auto-fetch
> now, build it after the processing slices.

## Scope

Backend and admin. No Ravilo client calls anything this spec adds, so the wire rule does not bind it. It adds the
**Fetchers** tab to the AI page that DEB-1253 built (`suggestions-as-a-tab-on-the-ai-page.md` FR-2, tab order
*Support · Fetchers · Suggestions*), an `autofetch` job on phase 270's AI rail, and a *Not on TMDB yet* queue.

The design is round 2, quarantined at `design/incoming/2026-10-14-admin-ai/` (`02ae8b21`), re-skinned to the stand-in
corpus under DEB-1246 (`8bc5c18f`) and carried in `design/app/ai.html`, `ai.js` and `ai.css`. It draws six states:
*Fetchers*, *Investigating*, *Found · review*, *Not a series*, *Not on TMDB yet* and *No AI key*. Where the picture and
this file disagree, this file wins and the picture is a defect. Three things in this file are not drawn yet (FR-14).

It amends nothing shipped. Phase 270 gains a job and an FR-270-8 line (its dated amendment is in this file's *Amends*
section and goes into 270 at build). Phase 183 paced TMDB only (its build note); the per-site lane here is new and
modelled on its limiter, which is not changed.

### What the household does by hand today

The household fetches broadcaster series with hand-run scripts kept outside this repository, one per title, plus an
NFO writer and a manifest-driven downloader that fails closed. Auto-fetch is the general form of those scripts. Their
rules are the household's, learned the hard way, and every one of them becomes a rule of the engine here:

| Hand-tool rule | Where it lands |
|---|---|
| Highest quality variant, best audio | FR-6.3 |
| Tag the spoken language at the first mux, never after with `mkvpropedit` | FR-6.4 |
| Look for the source's subtitle track every time, embed it, and check a chunk, not just the playlist | FR-6.4 |
| `Tracks` before the first `Cluster` (`mkvinfo -v`), language checked with `ffprobe` | FR-6.5 |
| Remux to a temporary name and swap on success; never overwrite a file that is there | FR-6.6 |
| Titles and overviews are the source's own wording verbatim, or empty | FR-8.2 |
| Staging metadata is never written into the library | FR-8.3 |
| The source's order is not an episode's identity for a dub; a mapping is verified, and anything new fails closed | FR-4.3 |
| A one-off programme is not automatically a film; a recurring annual broadcast is a series with year-seasons | FR-3.2 |

The scripts are not called and not replaced. They stay what they are: the tool for films, one-offs and anything
Auto-fetch refuses.

## Investigation findings

1. **The model writes a recipe, never code.** The drawn panel is called a *script*, but what it shows is declarative:
   `source · list · manifest · pattern · diff · fetch · tmdb · done`. Running code a model wrote from a third-party
   page's content is a remote-code-execution path for anyone who can put text on that page, and this server holds
   every key the household has. So the model's output is a **recipe**: data in a fixed schema, read by a fixed engine
   written in Kotlin (FR-4). The panel shows the recipe, and keeps the word *script* only as its heading.
2. **The model runs once per fetcher, not once per run.** A scheduled run is deterministic and costs nothing: it reads
   the listing, applies the recipe and downloads what is missing. The model is called to set a fetcher up and when the
   admin presses *Re-investigate page*. Never on a schedule (FR-5).
3. **The investigation cannot use the Batches API.** Phase 270 sends everything through Message Batches, which can
   take up to 24 hours. The investigation is watched live (*Investigating*, five steps, a *Stop* button), so it uses
   the synchronous Messages API at full price, under its own job's monthly limit (FR-5.4).
4. **Our server reads the site, the model never does.** Anthropic's own fetch tool reads the web as Anthropic's
   agent, from Anthropic's addresses. One of the two broadcaster sites the household uses today forbids Anthropic's
   agents by name in its `robots.txt`; both allow a general crawler on their programme pages, and the other asks for
   ten seconds between requests. So this server fetches, under its own name, within the `robots.txt` rules for a
   general crawler, and hands the model what it read (FR-2, FR-5.2). Whether a site that forbids AI agents should
   have its pages read *to* a model at all is the owner's call (Q3).
5. **A scheduled fetcher is a bot, and the first site that notices will block it.** Today nothing in jellystructure
   paces a download, and phase 183's limiter only paces requests. A site gets one lane for every fetcher pointed at
   it, and a block stops the whole site, not one fetcher (FR-2).
6. **Not on TMDB yet is not a tenth processing feature.** A fetched file is a new episode on disk like any other. It
   enters through *Find new & changed* and gets every enabled feature (`processing-runs-as-nine-features.md` FR-3.1).
   The queue is what *Metadata & artwork* reports when TMDB has no such episode, and it clears when a later re-check
   finds one (FR-9).
7. **The episode stills need no model.** The drawn copy says the model picked three frames. A fixed rule can pick
   them as well (no black frame, no title card, spread across the runtime), costs nothing, and sends no picture
   anywhere (Q4).

## Owner decisions

Asked on this task as one card, 2026-10-25. Until it is answered, this file is written to the recommended option of
each question, and the slices that depend on an answer say so.

- **Q1 — An episode TMDB does not have yet.** *Recommended:* file it into the library under the page's own numbering,
  and queue it under *Not on TMDB yet*, as drawn, **but only when the fetcher's pattern has been proven against the
  episodes TMDB does have** (FR-4.2). *Alternative:* hold it outside the library until TMDB has it or the admin approves
  its number.
- **Q2 — A source whose numbering is not its own** (a dub, a re-cut, a site that only lists in order). *Recommended:*
  the model proposes a mapping to TMDB from titles and descriptions, the admin approves it at save, and every new item
  after that **waits for the admin's approval** of the model's proposal before it is downloaded (FR-4.3). *Alternative:*
  out of scope; Auto-fetch refuses such a source, and it stays hand work.
- **Q3 — A site whose `robots.txt` forbids AI agents.** *Recommended:* nothing from that site is sent to the model.
  Auto-fetch may still set it up when the site's own listing carries season and episode numbers, with the model
  unused, and refuses it otherwise (FR-5.2). *Alternative:* our server is the fetcher, so its pages go to the model like
  any other site's.
- **Q4 — Episode stills.** *Recommended:* three frames picked by a fixed rule on this server; no frame leaves the house
  (FR-9.4). *Alternative:* the model picks them, as the drawn copy says, at a per-episode cost.

## Requirements

### FR-1 — The Fetchers tab and its gate

1. The **Fetchers** tab is present on the AI page whenever an AI key is set, the same key test the AI page already uses
   (non-blank, `suggestions-as-a-tab-on-the-ai-page.md` FR-1). `GET /api/ai/page` gains `"fetchers"`, in the fixed
   order *Support · Fetchers · Suggestions*.
2. **A switched-off AI, a revoked key or a spent limit does not remove the tab, and does not stop a fetcher.** A saved
   fetcher runs from its recipe with no model call (finding 2). What those states stop is a *new* investigation and
   *Re-investigate page*. Those buttons are shown, disabled, with the reason (`processing-runs-as-nine-features.md`
   FR-11): *AI is switched off in Settings ▸ AI* · *The key was refused — Settings ▸ AI* · *This month's Auto-fetch
   limit is spent ($n of $m)*.
3. With no key, the AI page is absent, as drawn (*No AI key*). Saved fetchers keep running; they are listed on
   Activity ▸ Schedule (FR-11), so a fetcher never runs where nobody can see it.

### FR-2 — One lane per site: pacing, `robots.txt` and being blocked

A **site** is the registrable domain of the page URL (`varpid.example.fo`), plus the hosts the recipe's listing,
manifest and media URLs resolve to, which are recorded on the recipe at save (FR-4.1). All fetchers on one site share
one lane.

1. **Identity.** Every request carries the User-Agent jellystructure already sends
   (`jellystructure/<version> (https://github.com/jeggy/jellystructure)`). It never pretends to be a browser, and never
   rotates its User-Agent, address or route to get past a refusal.
2. **`robots.txt`.** Read once a day per site and cached. The rules for a general crawler (`User-agent: *`) bind every
   page, listing and API request; media segments a manifest names are a player's traffic and are not checked against
   it. A path the rules forbid is never requested: an investigation that needs one stops with the reason
   (*this site's robots.txt does not allow /path*), and a saved fetcher whose listing becomes forbidden stops (FR-2.5).
   A site that forbids AI agents by name is recorded, and decides Q3.
3. **Pacing.**
   - Page, listing and API requests: at most one every **10 seconds**, or the site's own `Crawl-delay` when it asks for
     more. A limiter of its own, keyed by site (there is no generic per-host limiter: phase 183 paced TMDB only).
   - An investigation reads at most **25** requests from the site. A scheduled run reads only the listing it needs.
   - Downloads: **one at a time per site**, never in parallel, with **60 seconds** between two episodes.
   - At most **20 episodes per run** per fetcher. A first run on a long back-catalogue takes several runs; the card says
     *20 of 64 fetched · the rest on the next runs*.
   - A fetcher checks no more often than **every 6 hours**. The schedule's minute is spread by the fetcher's id, so two
     fetchers on *Daily · 04:00* do not start on the same second.
4. **Slowing down.** A `429` or `503` honours `Retry-After`, as FR-183-2 does for TMDB. Without one, the lane waits 1 hour, then 4, then
   24. A run that hits it stops where it is and resumes at the next run; nothing is lost, because the diff is recomputed
   every run (FR-6.1).
5. **Being blocked.** The lane **stops** on any of: a `401` or `403` on a URL that worked on a previous run; a challenge
   page (an HTML answer where the recipe expects JSON or a manifest, or a known challenge marker such as
   `cf-mitigated`); a third `429` in one day; `robots.txt` newly forbidding the listing. A stopped site:
   - runs nothing for any of its fetchers, and holds no retry timer;
   - shows on every one of its fetchers as **Site stopped** with the reason and the time, on the Dashboard as one row
     per stopped site, and as one notification (Notifications ▸ the existing owner webhook);
   - starts again only when the admin presses **Resume site**, which runs one listing request and goes back to
     *stopped* if that is refused too.

   A stop is never retried automatically, because a site that has blocked a client and sees it come back on a timer
   usually blocks it for good.
6. **A page that changed is not a block.** A listing that answers but yields nothing the recipe can read
   (*0 videos found*) marks that one fetcher **Needs re-investigation**, as drawn on the third row of the list. It
   downloads nothing and spends nothing until the admin presses *Re-investigate page*.

### FR-3 — What Auto-fetch accepts and what it refuses

1. **Series only.** The TMDB id must resolve to a TV series (`/tv/{id}`). A film id is refused as drawn: *this id is a
   film — Auto-fetch needs a series*. A page whose listing yields one item with no recurring title and no numbering is
   refused as drawn (*Not a series*), and nothing is written.
2. **A recurring annual broadcast is a series with year-seasons** (`S2026E01`), as the drawn *Royndin* example shows.
   The investigation looks for the recurrence (other years on the page, other editions on TMDB) before it calls a
   single item a film.
3. **No DRM.** A manifest that carries a DRM key system (Widevine, PlayReady, FairPlay) is refused: *this site protects
   its videos; Auto-fetch does not get past that*. Plain HLS AES-128 that any player decrypts with the key the manifest
   names is not DRM. Nothing behind a login is fetched, and no credential field exists.
4. **Not where another tool downloads.** A series Sonarr has, found by its TMDB id or by the target folder's path,
   is refused: *Sonarr already downloads this series*. That applies whether Sonarr monitors it or not. Sonarr adopts any
   visible file in a series folder it knows (phase 311), may number it from another catalogue (phase 160), and its
   upgrades replace files. Checked at save and again before every run. A series Sonarr takes on later stops that
   fetcher with the same reason.
5. **One target library.** The fetcher saves into one series library jellystructure manages, chosen at save from the
   mapped series libraries; with one, there is no choice.

### FR-4 — The recipe

1. **A recipe is data in a fixed schema, version 1:**
   - `source`: the page URL, and the hosts it resolves to;
   - `list`: how to read the episode list — one of `html` (a CSS selector for each item, and attributes for title, link,
     published date and description), `json` (a URL template on the same site and JSON paths for the same fields), or
     `ytdlp` (a site yt-dlp's own extractor already understands, reading its fields; FR-6.3);
   - `media`: how an item becomes a stream — a manifest URL template or a JSON path to it, or `ytdlp`;
   - `numbering`: either `source` (the item's own season and episode numbers, read by a pattern) or `mapped` (an
     explicit table, item id → `SxxEyy`, FR-4.3);
   - `pattern`, for `source` numbering: an ordered list of rules from a fixed grammar (*year in the title → season*;
     *"N. partur" or "(N:M)" → episode*; *S{nn}E{nn}*; *a field of the listing → season / episode*). A rule is a regular
     expression with named groups `season` and `episode`, checked by the engine for size and against catastrophic
     backtracking before it is stored;
   - `language`: the spoken language (ISO 639-2) and the subtitle language to look for;
   - `quality`: the highest variant, always.

   Nothing in a recipe is executed. A URL template may only name a host recorded in `source`.
2. **Proven before it is saved.** The model's recipe is accepted only when the engine, running it itself against the
   live site, gets the same answer the model gave (FR-270-4, *nothing trusted blindly*):
   - the listing yields at least two items, and every item has a title and a link;
   - every item maps to exactly one `SxxEyy`, and no two items map to the same one;
   - one manifest, chosen by the engine, resolves to a video variant `ffprobe` can read, with a duration;
   - **the pattern agrees with TMDB on every episode TMDB has**: for each mapped `SxxEyy` TMDB knows, the TMDB air
     date is within 7 days of the item's published date, or the TMDB episode name equals the item's title. Under Q1's
     recommendation, at least **two** such agreements, with none against, are what *proven* means. A fetcher whose
     pattern is not proven can still be saved, and then every episode TMDB lacks waits for the admin (FR-4.3's flow).

   Anything else is the *Not a series* state with the failing check as its reason, and nothing is written.
3. **Mapped numbering fails closed** (Q2's recommendation). For a source whose order is not an identity, the model
   proposes the item → `SxxEyy` table from the items' titles and descriptions against TMDB's episode names and
   overviews, and the review screen shows it row by row. Only rows the admin accepts are stored. A new item found on a
   later run is **not downloaded**: it is listed on the fetcher as *1 new item waiting for a number*, and the admin
   either types the number, or presses *Propose* (one model call, under the limit), and accepts it.
4. **Stored, versioned, readable.** The recipe is stored with the fetcher, with its schema version, the model and the
   date it was written. The fetcher's open row shows it read-only, as drawn. *Re-investigate page* writes a new recipe
   beside the old one and the admin saves or discards it; the old one runs until then.

### FR-5 — What the AI does, and what leaves the house

1. **The investigation is one conversation with tools**, on the job's chosen model, using the synchronous Messages
   API. The tools are this server's, not Anthropic's: `fetch(url)`, which this server performs under FR-2 and answers
   with the body trimmed to text and structure (scripts and styles removed, at most 60 kB per answer); `probe(url)`,
   which runs `ffprobe` on a manifest and answers with its variants; and `tmdb_series(id)`, which answers with the
   series' seasons and episode names, numbers and air dates. `fetch` refuses a URL whose host is not the page's site.
   The conversation ends with the recipe, constrained by `output_config.format`. At most **12 turns**; past that it
   stops as *Not a series* with *the page could not be worked out in 12 steps*.
2. **What is sent** (FR-270-8 gains this line): *the page URL and what this server read from that site during the
   investigation, and the TMDB series' name, seasons and episode list. Nothing from the library: which episodes the
   household has is worked out on this server, after the model has answered.* Under Q3's recommendation, a site whose
   `robots.txt` names Anthropic's agents gets **no model call at all**: the investigation runs the `ytdlp` and `json`
   readers the engine knows on its own, sets up a `source`-numbered fetcher when the listing itself carries season and
   episode numbers, and otherwise refuses with *this site does not allow AI agents to read it*.
3. **Page content is data, not instructions.** The system prompt says so; and nothing the model answers is acted on
   except through FR-4's schema and FR-4.2's proof. A page that tells the model to fetch another site, write a file or
   change the schedule can at worst produce a recipe that fails its proof.
4. **Under the spending limit.** A new job card *Auto-fetch* on Settings ▸ AI, with the same controls as the others:
   switch, model, monthly limit, spent, last run (FR-270-1). Because the investigation is not a batch, it is priced at
   the full rate. Before **each turn**, the worst case of the rest of the conversation (the turns left × the largest
   turn's input + `max_tokens`) is checked against the limit, and the investigation stops with *limit reached* before
   it would pass it. Every turn's `usage` goes to the `ai_usage` ledger. The starting estimate on the card: ~8 turns of
   ~15k input and ~1.5k output each, ≈ $0.90 an investigation on Opus 5, ≈ $0.36 on Sonnet 5.
5. **One investigation at a time**, server-wide, as drawn. *Stop* ends it, discards what it found, and writes nothing.

### FR-6 — A run

1. **Idempotent by construction.** A run reads the listing, maps every item, and diffs against the library: an
   `SxxEyy` that has a file in the series' folder is skipped, whoever put it there. A run that finds nothing new is a
   success (*no new episodes*), as drawn.
2. **When it runs.** At its schedule (*Every 6 h* · *Daily at* · *Weekly on*, the presets of
   `processing-runs-as-nine-features.md` FR-3.2, no Custom below 6 hours); on *Run now*; and on *Run all now*, which
   queues every enabled fetcher into its site's lane. A paused fetcher keeps its recipe and history. A run waits while
   someone is watching, like every background job (FR-262-1); *Run now* does not wait.
3. **The download.** The highest video variant and the best audio, written to MKV. An HLS or DASH manifest is read
   with the image's own `ffmpeg`, the way the hand scripts do it. `ytdlp` recipes need yt-dlp, which **is not in the
   image today**. It is a new dependency, pinned to one release and checked against its published checksum when the
   image is built, and it arrives in its own slice (*Build order*, slice 6). Until then a `ytdlp` recipe is refused with
   *this site needs a reader that is not installed yet*.
4. **Language and subtitles at the first mux.** The audio track is tagged with the recipe's language during the
   download's own mux, never afterwards. The source's subtitle rendition is looked for on every episode; when one
   exists and its first chunk holds cues, it is embedded with the same language tag, and no sidecar is left behind.
5. **Checked before it is placed.** `ffprobe` shows the tagged language and a duration within 10 % of the listing's,
   where the listing gives one; `mkvinfo -v` shows the first top-level `Tracks` before the first `Cluster`. A file that
   fails is deleted from the temporary directory and the run line says why; it is retried on the next run, and after
   three failures the item is marked *failed* until *Re-investigate page*.
6. **Placed atomically, never over anything.** The file is written under phase 311's work folder in its destination
   season folder (`<Season folder>/.jellystructure/`, which Sonarr, Radarr and Jellyfin all skip, and which the
   leftover sweep already cleans), then renamed into `<Series>/Season NN/<Series> - SxxEyy - <episode name>.mkv`
   (year-seasons as `Season 2026`, as drawn). The rename refuses to overwrite: if anything is at that path, the file is
   discarded and nothing is replaced. The series folder is created only on a fetcher's first placed episode. The
   episode name in the filename is the page's, cleaned of path characters, or absent when the page has none.
7. **Then jellystructure takes it from there.** The placed file is announced to Jellyfin by path (*Created*), exactly
   as a new file is today, and handed to the single-title run every new episode gets
   (`processing-runs-as-nine-features.md` FR-3.1). No per-item Jellyfin metadata refresh is ever triggered.
8. **What a run records.** One line per run on the fetcher (*+1 · S2026E01 (1080p, 1.9 GB) · not on TMDB → queued*, as
   drawn) and an Activity entry (FR-11). Delete removes the fetcher, its recipe and its history, **never a file**, as
   drawn.

### FR-7 — Where it sits among the processing features

A fetcher is a **source of new files**, not a processing feature. It does not appear in Settings ▸ Processing or the
Scan dialog, it is not switched by them, and a manual scan does not run fetchers. Its runs are listed on Activity
beside the features' (FR-11).

### FR-8 — Metadata: jellystructure's, and the page's own words where TMDB has none

1. **An episode TMDB has is an ordinary episode.** *Metadata & artwork* writes its NFO, artwork and Jellyfin data as for
   any other, in the language phase 184 chooses. The page's text is not used.
2. **An episode TMDB does not have** takes its title and overview from the page **verbatim, or empty**: the item's own
   heading and description, never joined, shortened, rewritten or translated. When the page has none, the field is
   empty and the queue says so plainly, as drawn (*source incomplete*). Its air date is the item's published date.
3. **That text lives in jellystructure's database**, beside the queue row, and reaches the library only through the
   NFO jellystructure itself writes (`write_nfo`). No staging folder is written into the library, and the episode stills
   live in the application's own data directory until the admin downloads one.
4. **TMDB's arrival wins, field by field.** When a later re-check finds the episode on TMDB, the episode becomes
   ordinary (FR-8.1) and the next NFO write is TMDB's — **except for a field TMDB leaves blank**, which keeps the
   page's text. A freshly added TMDB episode often has a number and no name yet. Today a metadata re-pull overwrites an
   episode's title and overview with whatever TMDB returns, blank included. So this needs a per-episode marker
   (*title/overview from the source page*) that the re-pull and the season sync read. Once TMDB has text for both
   fields, the marker goes and the page text stays on the queue row only as history.

### FR-9 — *Not on TMDB yet*

1. **What lands in it.** An episode a fetcher placed for which TMDB has no such `SxxEyy`. Under Q1's recommendation,
   only from a fetcher whose pattern is proven (FR-4.2); from any other, the episode is not placed until the admin
   accepts its number.
2. **What it holds**, as drawn: series and `SxxEyy`, the page's own label for it, when it was downloaded, the runtime,
   the page's episode name and overview with where each came from, the air date, and three stills.
3. **How it clears.** Every queued episode's season is re-checked on TMDB **daily**, whatever the release-age interval
   of *Metadata & artwork* says. That check asks TMDB explicitly. A scan does not ask TMDB about an episode that already
   has a title and an overview, so a scan alone would never clear a row that carries page text. When TMDB returns the episode, the row clears itself and the episode is ordinary
   (FR-8.4). *Mark as submitted* is the admin's note that they have entered it on TMDB; the row stays, marked, until TMDB
   returns it. Nothing is ever sent to TMDB: TMDB takes no submissions by API, and phase 307's rule holds anyway.
4. **Stills** (Q4's recommendation). Three frames picked on this server from the middle 80 % of the runtime: not black,
   not a near-duplicate of the frame before, and not in the first or last minute where title and credit cards sit;
   1920×1080 JPEG at the source's resolution or below, never upscaled. *Pick a different moment* takes a timestamp.
5. **Only fetched episodes.** An episode placed by hand that TMDB lacks is not listed here: there is no page text to
   carry across. Today such an episode has no state of its own (its TMDB episode id is simply empty, and the scan keeps
   whatever title it has), and that is unchanged.

### FR-10 — The screens

As drawn in round 2, with these changes:

1. **The review state** shows the proof (FR-4.2): *pattern agrees with TMDB on 3 of 3 episodes TMDB has*, or the
   disagreement row by row. For `mapped` numbering, every mapping row has an accept tick, all unticked.
2. **The fetchers list** gains two states per row: **Site stopped** (FR-2.5, with *Resume site*) and **n items waiting
   for a number** (FR-4.3). *Needs re-investigation* is the drawn third row.
3. **The still copy** reads *Three frames picked from the video* instead of *the model picked* (Q4).
4. **The bug-report link** (*File a bug report on GitHub*) is kept, pre-filled with the URL's host, the TMDB id and the
   five steps' outcomes. Never the page's content, as drawn, and never the full URL path: a path can carry a title.
5. The drawn *Investigating* spinner is an infinite animation, which the screenshot fence forbids (round 2's
   `MANIFEST.md`, *Open at promotion*); the promotion replaces it (FR-14).

### FR-11 — Activity

Each run is an entry on Activity ▸ History (*Auto-fetch · Royndin · +1 episode*), and Activity ▸ Schedule lists every
enabled fetcher with its next run, under the heading **Fetchers**, beside the processing features
(`processing-runs-as-nine-features.md` FR-8). A stopped site shows there as well.

### FR-12 — Routes

All admin-only, behind the admin's existing auth, not on the Ravilo wire:

- `GET /api/autofetch` — the fetchers, their sites' state, and the queue;
- `POST /api/autofetch/investigate` `{url, tmdbId}` → the investigation's id; its progress is pushed over the existing
  WebSocket as the five steps; `POST /api/autofetch/investigate/{id}/stop`;
- `POST /api/autofetch` (save a reviewed recipe), `PATCH`/`DELETE /api/autofetch/{id}`, `POST /api/autofetch/{id}/run`,
  `POST /api/autofetch/run-all`;
- `POST /api/autofetch/sites/{site}/resume`;
- `POST /api/autofetch/{id}/items/{itemId}/number` `{season, episode}` and `…/propose`;
- `GET /api/autofetch/queue/{episodeId}/still/{n}`, `POST /api/autofetch/queue/{episodeId}/submitted`.

The admin renders server-pushed state only.

### FR-13 — Configuration

`[autofetch]` in `config.toml`: `enabled` (default **false**; off means no fetcher runs and no investigation starts).
Fetchers, recipes, sites' state and the queue live in the database, not in `config.toml`. The pacing numbers of FR-2.3 are constants in code, not
settings: a household that wants to fetch faster than a site allows is not a case to support.

### FR-14 — Design work this file needs

Round 2's design stands. Three additions are not drawn, and the CSS is not promoted:

1. the *Site stopped* and *n items waiting for a number* row states (FR-10.2);
2. the proof line and the accept ticks on the review state (FR-10.1);
3. a still, non-animated *Investigating* step marker, and the promotion of `ai.css` into the production stylesheets
   with its `SCOPED_FILES` row and wrapper class (round 2's `MANIFEST.md`).

Those are one round for Rannvá, briefed from this file, with the stand-in corpus only.

## Stand-in data

`Royndin`, TMDB `9000001`, `varpid.example.fo/sendingar/royndin`, episode name `Útfall` (all cleared under DEB-1246);
`Nordvest` on `play.example.dk`. Tests and any new picture use only these. No real broadcaster page, title, slug or
TMDB id appears in this repository; a test that needs a live-looking listing uses a fixture served by the test itself.

## Acceptance

1. With a key set, the AI page shows *Support · Fetchers · Suggestions*. With AI switched off, *Investigate* is
   disabled with its reason and a saved fetcher still runs.
2. A stand-in series page served by the test with year-season titles: *Investigate* shows five steps, the review
   shows the mapping and *pattern agrees with TMDB on 2 of 2*, *Save* places the missing episodes as
   `Season 2026/Royndin - S2026E01 - Útfall.mkv`, each with the audio language tagged, `Tracks` before `Cluster`, and
   no sidecar. The second run says *no new episodes* and requests only the listing.
3. An episode TMDB lacks appears under *Not on TMDB yet* with the page's own name and overview, or with *source
   incomplete* when the page has none. When the test's TMDB starts returning it, the next daily re-check clears it.
4. The test site answers `429` with `Retry-After: 120`: the run stops and the lane waits 120 s. It answers `403` on the
   listing: the site is *Site stopped* on every fetcher, on the Dashboard and in one notification, and no request
   reaches it until *Resume site*.
5. Requests to the test site are never closer than 10 s (or its `Crawl-delay`), and two downloads never overlap.
6. A film id, a one-item page, a DRM manifest and a Sonarr-monitored id are each refused with their reason, and
   nothing is written.
7. A page that tells the model to fetch another host gets a `fetch` refusal, and the investigation's recipe fails its
   proof or carries no foreign host.
8. A limit set below one investigation's worst case: *Investigate* stops before the first turn with *limit reached*,
   and no request reaches Anthropic.

## Non-goals

- Films and one-off clips (FR-3.1), and anything behind a login or DRM (FR-3.3).
- Writing to TMDB, ever. *Mark as submitted* is a note.
- Replacing the household's hand scripts. They stay, outside this repository.
- A model call on a schedule.

## Amends

- **Phase 270**, at build, a dated amendment: FR-270-1 gains the *Auto-fetch* card; FR-270-8 gains FR-5.2's line;
  FR-270-6's check runs per turn for this job, which is not a batch (FR-5.4).
- **`suggestions-as-a-tab-on-the-ai-page.md` FR-2**: `"fetchers"` joins the tab list (FR-1.1).

## Code anchors (`main` at `1d5ed554`)

`BE` = `src/linuxX64Main/kotlin/dev/jellystructure`.

| Need | Where it is today |
|---|---|
| AI client | `BE/ai/AnthropicClient.kt`: batches and `GET /v1/models` only. **No synchronous `/v1/messages` call and no tool use anywhere.** FR-5.1 adds both |
| AI config, a job's card | `AiConfig` / `AiJobConfig`, `BE/config/AppConfig.kt:203-270`; jobs named in `BE/ai/AiRequests.kt:27-28` |
| Ledger, worst-case check | `BE/ai/AiJobs.kt`, `BE/ai/AiPricing.kt` |
| A rate limiter to model the site lane on | `TmdbRateLimiter`, `BE/tmdb/TmdbClient.kt:86` (`Retry-After`, backoff, jitter); `FixedRateLimiter`, `BE/music/FixedRateLimiter.kt:16` |
| Outbound concurrency gate | `BE/OutboundHttp.kt:52`; run on `GateClass.BACKGROUND` (`BE/ops/GateClass.kt`) |
| A schedule | No generic scheduler. The processing loop is `BE/Main.kt:800-846`, cron in `BE/cron/CronEvaluator.kt`. A fetcher tick is its own loop beside it |
| Work files, atomic placement | `WorkFiles` (`src/commonMain/kotlin/dev/jellystructure/media/WorkFiles.kt:38-82`), `WorkFileSweep.kt`, `scripts/check-workfiles.sh`; a rename that refuses to overwrite: `ContainerConvert.rename`, `BE/media/ContainerConvert.kt:34` |
| ffmpeg, ffprobe, mkvtoolnix | in the runtime image (`Dockerfile:131-133`); runners `BE/media/FfmpegRunner.kt`, `FfprobeRunner.kt`, under `BE/ops/ProcessGate.kt`. **yt-dlp is not in the image** |
| Announce a new file to Jellyfin | `JellyfinClient.notifyLibraryMediaUpdated(path, "Created")`, `BE/auth/JellyfinClient.kt:915` |
| Episode with no TMDB match | no state today: `tmdbEpisodeId` stays null (`src/commonMain/kotlin/dev/jellystructure/model/Media.kt:172`); the scan skips TMDB for an episode with a title and an overview (`BE/media/Scanner.kt:696-703`); the re-pull overwrites both from TMDB, blank included (`Scanner.kt:1376-1393`, `syncSeason` `:1246-1253`) — FR-8.4's marker goes here |
| Episode NFO | `NfoWriter.writeEpisode`, `BE/nfo/NfoWriter.kt:82,154-201` |
| Sonarr lookup | `ArrClient.findSeriesIdByTmdbId` `:185`, `findSeriesIdByPath` `:114`, `BE/arr/ArrClient.kt` |
| Activity | `BE/media/ActivityLog.kt` (`startRun`/`finishRun`) |
| Library roots | `AppConfig.libraries` / `LibraryMapping`, `AppConfig.kt:12,735-743` |

## Build order

One engineer, one piece at a time, each its own task, `blocked` by the one before. All of it comes after the
processing slices.

1. **Rannvá: the round of FR-14**, and the promotion of `ai.css`. Can run beside slice 2.
2. **Backend: the engine, one site, no AI.** The recipe schema and its validation (FR-4.1, FR-4.2 without the model),
   the `html` and `json` readers with ffmpeg for HLS/DASH, the site lane (FR-2), a run (FR-6), placement and the
   hand-off to processing (FR-6.6, FR-6.7), the Sonarr refusal (FR-3.4), the `[autofetch]` config and the
   save/run/delete routes. A recipe is entered through the route by hand. Proven against a test-served stand-in site.
   This is the slice that runs on one site, and it is useful on its own. It needs a migration (fetchers, recipes,
   sites, runs).
3. **Backend + admin: Not on TMDB yet** (FR-8, FR-9, the queue's routes, the FR-8.4 marker in the re-pull), and the
   Fetchers tab's list and queue (FR-1, FR-10.2, FR-11), drawn from slice 1's CSS.
4. **Backend + admin: the investigation** (FR-5, FR-4.2 with the model, FR-10.1): the synchronous Messages call with
   tools in `AnthropicClient`, the *Auto-fetch* job card, and 270's amendment. This is the first slice that spends
   money.
5. **Backend + admin: mapped numbering** (FR-4.3) — only if Q2 is answered *as recommended*.
6. **Image + backend: the yt-dlp reader** (FR-6.3), pinned and checksummed. Under Q3's recommendation this is what
   lets a site that forbids AI agents be set up at all.

Each piece files its own QA row when built, with a written plan and no named seat.
