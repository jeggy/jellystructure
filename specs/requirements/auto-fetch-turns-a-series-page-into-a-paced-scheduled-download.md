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

**Amended 2026-10-27 (DEB-2626)**, before any of it was built: a fetcher may be **page-only**, with no TMDB id
(FR-15); a page-only fetcher may number a daily programme **by its air date** (FR-4.5); and every fetcher has a
**retention** setting, *keep the newest N* with a default of 12, which makes FR-16 the one path in this file that
deletes a file. The lines this changes elsewhere point at those FRs.

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
   general crawler, and hands the model what it read (FR-2, FR-5.2). A site that forbids AI agents by name gets the
   same treatment as any other: the owner decided so (Q3).
5. **A scheduled fetcher is a bot, and the first site that notices will block it.** Today nothing in jellystructure
   paces a download, and phase 183's limiter only paces requests. A site gets one lane for every fetcher pointed at
   it, and a block stops the whole site, not one fetcher (FR-2).
6. **Not on TMDB yet is not a tenth processing feature.** A fetched file is a new episode on disk like any other. It
   enters through *Find new & changed* and gets every enabled feature (`processing-runs-as-nine-features.md` FR-3.1).
   The queue is what *Metadata & artwork* reports when TMDB has no such episode, and it clears when a later re-check
   finds one (FR-9).
7. **The episode stills need no model.** The drawn copy says the model picked three frames. A fixed rule can pick
   them as well (no black frame, no title card, spread across the runtime), costs nothing, and sends no picture
   anywhere. The owner chose the fixed rule (Q4).

## Owner decisions

Asked on this task as one card (`6cad194f`) and answered by the owner on 2026-10-25 at 18:46Z. Three answers took the
recommended option and one did not (Q3).

- **Q1 — An episode TMDB does not have yet.** *Decided:* file it into the library under the page's own numbering,
  and queue it under *Not on TMDB yet*, as drawn, **but only once the fetcher's pattern has been proven against the
  episodes TMDB does have** (FR-4.2). Rejected: holding it outside the library until TMDB has it or the admin approves
  its number.
- **Q2 — A source whose numbering is not its own** (a dub, a re-cut, a site that only lists in order). *Decided:*
  the model proposes a mapping to TMDB from titles and descriptions, the admin approves it at save, and every new item
  after that **waits for the admin's approval** of the model's proposal before it is downloaded (FR-4.3). Rejected:
  leaving it out of scope.
- **Q3 — A site whose `robots.txt` forbids AI agents by name.** *Decided, against the recommendation:* this server is
  the one that fetches, under its own name and within the general-crawler rules, so **what it read goes to the model
  like any other site's** (FR-5.2). Rejected: sending such a site nothing and setting it up without the model.
- **Q4 — Episode stills.** *Decided:* three frames picked by a fixed rule on this server; no frame leaves the house
  (FR-9.4). Rejected: the model picking them.

Owner, 2026-10-27, on DEB-1076, when a daily programme was moved onto Auto-fetch:

- **A programme TMDB will never have.** *"[it] will most likely not ever be added to tmdb"* (08:22Z). This led to the
  page-only fetcher (FR-15).
- **A cap on how many it keeps.** *"in the ai fetcher have support for some max number of episodes"* (08:20Z), and
  then *"Let's make 12 the default, but this should be a configuration within the auto-fetcher setup"* (08:26Z).
  This led to retention (FR-16).

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
   A rule that names only AI agents (Anthropic's among them) does not bind this server, which is not one of those
   agents (Q3). The model never fetches anything itself (FR-5.1).
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
   refused as drawn (*Not a series*), and nothing is written. A fetcher saved without a TMDB id on purpose is a
   page-only fetcher (FR-15, amended 2026-10-27). The page test in this item still applies to it.
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
   - `numbering`: `source` (the item's own season and episode numbers, read by a pattern), `mapped` (an
     explicit table, item id → `SxxEyy`, FR-4.3), or `date` (the item's published date, page-only fetchers only,
     FR-4.5);
   - `series`, page-only fetchers only (FR-15.3): where the page gives the programme's name, description and image,
     in the same form as `list`'s fields;
   - `retention`: `all`, or the newest `N` with *mirror the page* on or off (FR-16);
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
     date is within 7 days of the item's published date, or the TMDB episode name equals the item's title. Under Q1, at
     least **two** such agreements, with none against, are what *proven* means. A fetcher whose
     pattern is not proven can still be saved, and then every episode TMDB lacks waits for the admin (FR-4.3's flow).

   Anything else is the *Not a series* state with the failing check as its reason, and nothing is written. A
   page-only fetcher skips the TMDB check and has two of its own instead (FR-15.8).
3. **Mapped numbering fails closed** (Q2). For a source whose order is not an identity, the model
   proposes the item → `SxxEyy` table from the items' titles and descriptions against TMDB's episode names and
   overviews, and the review screen shows it row by row. Only rows the admin accepts are stored. A new item found on a
   later run is **not downloaded**: it is listed on the fetcher as *1 new item waiting for a number*, and the admin
   either types the number, or presses *Propose* (one model call, under the limit), and accepts it.
4. **Stored, versioned, readable.** The recipe is stored with the fetcher, with its schema version, the model and the
   date it was written. The fetcher's open row shows it read-only, as drawn. *Re-investigate page* writes a new recipe
   beside the old one and the admin saves or discards it; the old one runs until then. Retention is the one part of
   a saved recipe the admin edits in place (FR-16.1).
5. **Numbered by air date** (amended 2026-10-27, DEB-2626). This applies to a page-only fetcher of a programme whose
   episodes have no numbers of their own, such as a daily or weekly programme.
   - **Identity.** An item's published date is its identity, as a calendar date, the way the page prints it. Its
     season is that date's year. Its episode number is that date's **day of the year**, from 1 to 366: 27 October 2026
     is `S2026E300`.
   - **Filename.** Placed per FR-6.6 as
     `<Series>/Season 2026/<Series> - S2026E300 - 2026-10-27 - <episode name>.mkv`. The ISO date is always there. The
     episode name is there only when the page has one.
   - **Why a number and not Jellyfin's date-named episode file** (`<Series> - 2026-10-27.mkv`): such a file has no
     episode number anywhere in jellystructure.
     - `parseSeasonEpisodes` has no date form (`Scanner.kt:72`), so the episode is stored with a null number.
     - The Ravilo series detail sends `episode_number` as a non-null `Int` (`shared/…/tv/Models.kt:794`), and fills a
       null with `0` (`tv/DetailService.kt:170`). Every episode of the programme would read `S2026E00`, in no order,
       on every installed client.
     - A wire change cannot fix clients that are already installed.

     A day-of-the-year number fits `SEASON_EP_RE`'s three-digit episode. It sorts by date. It is the same whichever
     run places the episode, so retention deleting older episodes never renumbers the newer ones. The ISO date
     straight after the code also stops a page title that starts with `E` from being read as an episode range
     (`EP_RANGE_TAIL_RE`).
   - **Two items on one date.** These fail FR-4.2's *no two items map to the same one* at save. On a later run, the
     second item is not downloaded. The run line says *shares 2026-10-27 with an episode already placed*. The fetcher
     carries on.
   - **Not for a TMDB fetcher.** TMDB numbers its own episodes, so `date` is offered only to a page-only fetcher.
     Showing the air date in Ravilo instead of the code would be a Ravilo spec of its own, not part of this one.

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
   household has is worked out on this server, after the model has answered.* This applies to every site, including a
   site whose `robots.txt` names Anthropic's agents: the owner decided (Q3) that what this server reads under its own
   name, within the general-crawler rules, goes to the model as it would from any other site. A page-only
   investigation has no `tmdb_series` tool and sends nothing from TMDB (FR-15.9).
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
   success (*no new episodes*), as drawn. An item that would fall outside the fetcher's retention the moment it landed
   is skipped as well, and so is one retention has already deleted (FR-16.4).
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
   drawn. Retention is the one thing that deletes a file, and only under FR-16's rules (amended 2026-10-27). When a
   fetcher is deleted or paused, or its retention is set to *All*, nothing is deleted. Files it placed stay in the
   library as ordinary files.

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

1. **What lands in it.** An episode a fetcher placed for which TMDB has no such `SxxEyy`. Under Q1,
   only from a fetcher whose pattern is proven (FR-4.2); from any other, the episode is not placed until the admin
   accepts its number. A page-only fetcher's episodes never land here (FR-15.6).
2. **What it holds**, as drawn: series and `SxxEyy`, the page's own label for it, when it was downloaded, the runtime,
   the page's episode name and overview with where each came from, the air date, and three stills.
3. **How it clears.** Every queued episode's season is re-checked on TMDB **daily**, whatever the release-age interval
   of *Metadata & artwork* says. That check asks TMDB explicitly. A scan does not ask TMDB about an episode that already
   has a title and an overview, so a scan alone would never clear a row that carries page text. When TMDB returns the episode, the row clears itself and the episode is ordinary
   (FR-8.4). *Mark as submitted* is the admin's note that they have entered it on TMDB; the row stays, marked, until TMDB
   returns it. Nothing is ever sent to TMDB: TMDB takes no submissions by API, and phase 307's rule holds anyway.
4. **Stills** (Q4). Three frames picked on this server from the middle 80 % of the runtime: not black,
   not a near-duplicate of the frame before, and not in the first or last minute where title and credit cards sit;
   1920×1080 JPEG at the source's resolution or below, never upscaled. *Pick a different moment* takes a timestamp.
5. **Only fetched episodes.** An episode placed by hand that TMDB lacks is not listed here: there is no page text to
   carry across. Today such an episode has no state of its own (its TMDB episode id is simply empty, and the scan keeps
   whatever title it has), and that is unchanged.

### FR-10 — The screens

As drawn in round 2, with these changes:

1. **The review state** shows the proof (FR-4.2): *pattern agrees with TMDB on 3 of 3 episodes TMDB has*, or, with
   fewer than two agreements, that the pattern is *not proven* and what that means: every episode TMDB lacks will wait
   for the admin's number. A disagreement is not a review state: it fails the proof, so it is the failed state, its
   reason naming the check and the disagreeing rows listed under it (corrected 2026-10-25, DEB-2481: this line said
   the review state showed the disagreement, which FR-4.2 rules out). For `mapped` numbering, every mapping row has an
   accept tick, all unticked.
2. **The fetchers list** gains two states per row: **Site stopped** (FR-2.5, with *Resume site*) and **n items waiting
   for a number** (FR-4.3). *Needs re-investigation* is the drawn third row.
3. **The still copy** reads *Three frames picked from the video* instead of *the model picked* (Q4).
4. **The bug-report link** (*File a bug report on GitHub*) is kept, pre-filled with the URL's host, the TMDB id and the
   five steps' outcomes. Never the page's content, as drawn, and never the full URL path: a path can carry a title.
5. The drawn *Investigating* spinner is an infinite animation, which the screenshot fence forbids (round 2's
   `MANIFEST.md`, *Open at promotion*); the promotion replaces it (FR-14).
6. **The page-only choice and retention** (amended 2026-10-27, DEB-2626; not drawn yet, FR-14.4):
   - **The form.**
     - It gains the tick *This programme is not on TMDB* under the TMDB id (FR-15.1).
     - Ticked, the id field is disabled and empty. Its hint reads *The episodes keep the page's own titles and
       descriptions, and nothing asks TMDB*.
     - The *series only* badge and the series test stay.
   - **The review state, page-only.**
     - The *Series* line reads *Dagbók · page only · not on TMDB* in place of the TMDB id and the *confirmed series*
       mark.
     - The proof line reads *12 items, each with its own date*, in place of the TMDB agreement.
     - The *Becomes* column shows the code and the date (`S2026E300 · 2026-10-27`).
     - The echo line under the form shows the page's own name and image, not TMDB's.
   - **The review state, every fetcher.** The settings box gains **Keep**, between *Check for new episodes* and *Run
     the first time*.
     - A choice of *All episodes* or *The newest [12]*, with the number editable from 1 to 999.
     - A tick under it, *Also delete an episode once it leaves the page*, shown only with *The newest*.
     - Its hint names the cost in plain words, from the server's own count: *Keeps 12 of the 12 on the page. Older
       ones are deleted from the library.*
     - The defaults are in FR-16.1.
   - **The fetcher's open row** shows the same **Keep** control, editable in place. It names what a change will do
     before it is saved: *Saving this deletes 4 episodes at the next run*.
   - **The run line** gains the deletions, one line each, as FR-16.6 words them.
   - **The fetchers list.** A row with retention shows it beside the schedule: *Daily · 04:00 · keeps newest 12*.

### FR-11 — Activity

Each run is an entry on Activity ▸ History (*Auto-fetch · Royndin · +1 episode*), and Activity ▸ Schedule lists every
enabled fetcher with its next run, under the heading **Fetchers**, beside the processing features
(`processing-runs-as-nine-features.md` FR-8). A stopped site shows there as well. A run that deleted something says so
in its entry (*Auto-fetch · Dagbók · +1 episode · −1 by retention*). Each deletion is a line under that entry
(FR-16.6).

### FR-12 — Routes

All admin-only, behind the admin's existing auth, not on the Ravilo wire:

- `GET /api/autofetch` — the fetchers, their sites' state, and the queue;
- `POST /api/autofetch/investigate` `{url, tmdbId}` → the investigation's id; its progress is pushed over the existing
  WebSocket as the five steps; `POST /api/autofetch/investigate/{id}/stop`;
- `POST /api/autofetch` (save a reviewed recipe), `PATCH`/`DELETE /api/autofetch/{id}`, `POST /api/autofetch/{id}/run`,
  `POST /api/autofetch/run-all`;
- `POST /api/autofetch/{id}/retention/preview` `{retention}` → what saving that retention would delete at the next
  run, counted by the server (FR-10.6, FR-16.1). Saving it is the `PATCH`. `POST /api/autofetch/investigate` takes
  `{url, tmdbId: null, pageOnly: true}` for a page-only fetcher (FR-15.1);
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

4. **Added 2026-10-27 (DEB-2626):** the page-only tick, the page-only review state, the **Keep** control on the
   review state and on the open row, the deletion lines, and the list row's retention note (FR-10.6). The round of
   items 1–3 has already been drawn and promoted, so this one is a small addendum round of its own. It must land
   before slice 3 builds the Fetchers tab. Slice 2 needs no picture.

### FR-15 — A page-only fetcher (amended 2026-10-27, DEB-2626)

A programme the household wants may never reach TMDB (owner, 2026-10-27). A **page-only** fetcher is one saved with
no TMDB id, on purpose. Everything in this file holds for it except what this FR changes.

1. **Chosen, never inferred.** The admin ticks *This programme is not on TMDB* (FR-10.6), and the investigation is
   started with `pageOnly: true`. A blank id without the tick is refused: *Auto-fetch needs a TMDB id, or tick "not on
   TMDB"*. So a forgotten id never makes a page-only fetcher. A page-only fetcher stays page-only. Giving it a TMDB id
   later means making a new fetcher (*Non-goals*).
2. **What still holds:**
   - FR-3.1's page test: a listing with one item and no recurrence is *Not a series*;
   - FR-3.2, FR-3.3 and FR-3.5;
   - the whole of FR-2;
   - FR-6, with FR-4.5's numbering when the recipe uses `date`.

   FR-3.4's Sonarr check uses the target folder's path only, because there is no id to look up.
3. **The series' own metadata comes from the page.** The recipe's `series` block (FR-4.1) says where the programme
   page gives its name, description and image. They are read at save and on *Re-investigate page*, never on a
   scheduled run, so a run asks the site for nothing more than the listing.
   - **Name.** It fills the review's *Name* field. The admin may edit it. What is saved becomes the series folder's
     name and `tvshow.nfo`'s title.
   - **Description.** It is the `tvshow.nfo` plot, verbatim or empty.
   - **Image.** It becomes the series poster. It is downloaded once from a host recorded in `source`, and never
     upscaled.

   No TMDB, IMDb or TVDB id is written (`NfoWriter.buildTvShowXml` already writes those only when present). The page's
   name and description are never translated, shortened or joined, just as FR-8.2 rules for episodes.
4. **An episode's metadata is always FR-8.2's.** Its title and overview are the page's words, verbatim or empty. Its
   air date is the item's published date. They live on the fetcher's item row in jellystructure's database (FR-8.3),
   and reach the library only through the NFO jellystructure writes.
5. **Nothing asks TMDB about it, ever.**
   - A series fed by a page-only fetcher is marked as such in the database. The scan reads the mark: it never
     searches TMDB for that series, and never takes a TMDB id for it from Jellyfin's provider ids.
   - The metadata re-pull and the season sync leave its episodes alone. The scan takes each episode's title, overview
     and air date from the fetcher's item row, matched by the placed path.
   - Today a series with no TMDB id is searched by its title (`Scanner.kt:589-590`, and the re-pull at `:1365`). A
     short programme name can match a stranger's series on TMDB, and that match would overwrite the page's words.
     This mark is FR-8.4's marker made permanent and moved up to the series.
   - *Metadata & artwork* still writes the NFOs and the thumbnails, from that text.
6. **FR-9 does not apply.** A page-only fetcher's episodes never enter *Not on TMDB yet*, and nothing re-checks TMDB
   for them. FR-8.4 does not apply either: there is no TMDB arrival to wait for. A permanent queue of a daily
   programme, emptied again by retention, would only be noise.
7. **The thumbnail follows FR-9.4's rule.** The engine picks frames by FR-9.4's rule and writes the first as the
   episode's `<base>-thumb.jpg`, the still Ravilo already serves (R133). There are no three stills to choose from and
   no *Pick a different moment*, because those belong to the queue. The page's own image is the programme's, never an
   episode's.
8. **The proof (FR-4.2) without TMDB.** All of these must hold:
   - the listing yields at least two items, each with a link and with either a title or a published date;
   - every item maps to exactly one identity, and no two items to the same one;
   - one manifest probes;
   - the `series` block yields a non-empty name.

   The TMDB agreement check is skipped. Q1's *proven* has nothing to decide, because nothing goes to the queue.
   `mapped` numbering is not offered, because it maps onto TMDB's episodes. The numbering is `source` or `date`.
9. **The investigation (FR-5).**
   - The `tmdb_series` tool is not offered.
   - The model is told the fetcher is page-only, and that it must find the `series` block.
   - For this fetcher, FR-5.2's line reads: *the page URL and what this server read from that site during the
     investigation. Nothing from TMDB, and nothing from the library.*

### FR-16 — Retention: keep the newest N (amended 2026-10-27, DEB-2626)

Owner, 2026-10-27: *"support for some max number of episodes"*, and *"Let's make 12 the default, but this should be a
configuration within the auto-fetcher setup"*. Until now nothing in this file deleted a file (FR-6.8). This FR is the
one path that does, and its rules are what make that safe.

1. **The setting.** Each fetcher has **Keep**: *All episodes*, or *The newest N* (an integer from 1 to 999, 12 when
   first shown). Under *The newest N* there is one more choice, **Also delete an episode once it leaves the page**.
   - It is set on the review state and edited later on the fetcher's open row (FR-10.6). It is stored on the fetcher
     (FR-4.1's `retention`), and editing it does not need *Re-investigate page*.
   - **The default depends on the fetcher.** A page-only fetcher starts at *The newest 12*, with *leaves the page* on,
     so its folder is what the page lists. A TMDB fetcher starts at *All episodes*. On a TMDB fetcher, retention is
     the admin's choice, never a default. A default that deleted a series' back-catalogue would be a break every
     viewer of that series notices.
   - Before a change is saved, the server counts what it would delete at the next run (FR-12's preview). The screen
     shows that count, and never a count of its own.
2. **It deletes only what this fetcher placed.**
   - Every placed episode is recorded on the fetcher's item row: the placed path and the item id. Retention considers
     only those rows.
   - A file a processing feature rewrote in place under phase 311's swap is still the fetcher's, because its path is
     unchanged.
   - When the recorded path no longer holds a file, the row is released and nothing is deleted. That covers a file
     moved or renamed by hand, and one deleted by someone else.
   - A file placed by hand in the same folder is never counted and never deleted, whatever its number. Neither is
     anything another fetcher placed.
3. **What *the newest N* means.**
   - The episodes are ordered by air date (the item's published date), newest first, and ties go by listing order.
   - N counts episodes **in the library**, not items on the page. A download that failed never makes room by deleting
     an older episode, so the folder never drops below the N it already had.
   - Episodes past the N-th are deleted at the end of the run.
4. **What a run downloads.** Only an item that is among the newest N of everything this fetcher knows: the episodes it
   has placed that are still in the library, together with the items on the page, ordered as in FR-16.3.
   - A first run on a page with 64 items and *the newest 12* downloads 12. It never downloads 20 and then deletes 8.
   - An episode retention deleted is older than the N the fetcher still holds. So it is never downloaded again, and no
     separate mark is needed (FR-6.1).
   - Raising N lets older items that are still on the page back in at the next run. That is what raising it means.
     Lowering N deletes at the next run, and the preview has already said how many (FR-16.1).
5. ***Leaves the page.*** When this is on, an episode whose item is no longer in the listing is deleted, but only when
   **all** of the following hold, so a bad read can never empty the library:
   - the listing was read in full in this run: a `2xx`, parsed by the recipe, every page of a paged listing read;
   - the listing yielded at least one item;
   - at least one of its items is one this fetcher has already placed, so a listing that shares nothing with the
     library is a changed page (FR-2.6, *Needs re-investigation*) and deletes nothing;
   - the item was missing from this read and from the previous full read, at least 6 hours apart, so one odd read
     deletes nothing.

   A failed read, a blocked site (FR-2.5), a `429` (FR-2.4), an empty listing (FR-2.6) or a run stopped part-way is
   never evidence that something has gone. Such a run **applies no retention at all**, neither rule. *The newest N*
   needs no page evidence, but it still waits for a run whose listing read succeeded.
6. **How a file is deleted.** jellystructure has no delete path for a library file today. The only deletion that tells
   Jellyfin is Dolby Vision version removal (`FileFixService.runRemove`, `filefix/FileFixService.kt:637`, notified at
   `Main.kt:520-526`). This slice builds one, modelled on it.
   - **What goes.** The episode file, and exactly the sidecars jellystructure wrote for it: its `<base>.nfo` and its
     `<base>-thumb.jpg`, named in full and never matched by a prefix. A season folder is removed only when nothing is
     left in it except jellystructure's own empty `.jellystructure/` work folder.
   - **What happens after.** The episode leaves jellystructure's database. Jellyfin is told by path with
     `"Deleted"`, the same call FR-6.7 makes with `"Created"`. The title's history records it, as `runRemove` does.
   - **Never while it plays.** An episode open in a playback session on any device is never deleted. It waits for the
     next run, and that includes *Run now*.
   - **Each deletion is recorded.** It is a line on the fetcher's run record (*−1 · S2026E286 (2026-10-13) · newest 12
     kept*, or *· left the page*) and a line under the run's Activity entry (FR-11). A deletion that fails is a line
     too. It is retried at the next run, and is never retried in a loop.
7. **It is never a notification.** A deletion is the setting doing what it says. The run record and Activity are
   where it is seen. A run that wanted to delete and found that it could not, because of one of FR-16.5's guards, says
   so on its run line: *left the page: not deleted, the listing could not be read in full*.

## Stand-in data

`Royndin`, TMDB `9000001`, `varpid.example.fo/sendingar/royndin`, episode name `Útfall` (all cleared under DEB-1246);
`Nordvest` on `play.example.dk`. For a page-only, date-numbered fetcher (FR-15, FR-4.5), use `Dagbók` at
`varpid.example.fo/sendingar/dagbok`. It is a daily programme with no TMDB id, a 12-item listing dated 2026-10-16 to
2026-10-27, and generic episode headings `Dagbók · 16. oktober` and so on (added 2026-10-27, DEB-2626). Tests and any
new picture use only these. No real broadcaster page, title, slug or
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

Added 2026-10-27 (DEB-2626), each against a stand-in site the test serves itself:

9. **A page-only fetcher.**
   - A blank TMDB id without the tick is refused.
   - With the tick, `Dagbók`'s 12 items are placed as
     `Dagbók/Season 2026/Dagbók - S2026E300 - 2026-10-27 - Dagbók · 27. oktober.mkv`, and so on.
   - `tvshow.nfo` carries the page's name and description and no TMDB id, and the poster is the page's image.
   - Each episode's NFO carries the page's heading and description verbatim, and each has a `-thumb.jpg`.
   - Two scans and a metadata re-pull later, the test's TMDB has received **no request** about the series, even
     though it answers a title search for `Dagbók` with a series of that name.
   - Nothing appears under *Not on TMDB yet*.
10. **Date numbering.**
    - `parseSeasonEpisodes` reads `S2026E300 - 2026-10-27 - E3 …` as season 2026, episode 300, and no range.
    - The Ravilo series detail lists the 12 by date with 12 different codes.
    - A listing with two items on 2026-10-20 fails the proof at save. On a later run, a second item on a date already
      placed is skipped with its line.
11. **The newest 12.** The page grows to 13 items.
    - The run downloads the new one and deletes the oldest: the file, its NFO and its thumb.
    - Jellyfin gets `"Deleted"` for that path, and the run record and Activity each carry the line.
    - The next run does not download the deleted item again.
    - A file placed by hand in the season folder with an older number is never touched.
12. **A bad read deletes nothing.** With *leaves the page* on, a single run sees each of these: a `503`, a `403`, an
    empty listing, a listing that shares no item with the library, and a listing missing 5 items once. Each run
    deletes nothing, and says so where FR-16.7 says it should. The 5 are deleted only after a second full read at least
    6 hours later still lacks them.
13. **Playback holds a deletion.** An episode open in a playback session survives the run that would delete it. It is
    deleted at the first run after the session ends.
14. **Defaults and preview.**
    - A new TMDB fetcher's review shows *All episodes*, and a page-only one's shows *The newest 12* with *leaves the
      page* on.
    - Lowering 12 to 8 on the open row previews *deletes 4 episodes at the next run* before saving, and the next run
      deletes exactly those 4.

## Non-goals

- Films and one-off clips (FR-3.1), and anything behind a login or DRM (FR-3.3).
- Writing to TMDB, ever. *Mark as submitted* is a note.
- Replacing the household's hand scripts. They stay, outside this repository.
- A model call on a schedule.
- Turning a page-only fetcher into a TMDB one, or the reverse. A new fetcher on the same folder skips what is already
  there (FR-6.1), but it does not take over the old fetcher's placed rows, so its retention does not count them
  (FR-16.2).
- Deleting anything a fetcher did not place, and any retention outside Auto-fetch.
- Showing the air date in place of `SxxEyy` on a Ravilo client (FR-4.5).

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
| Episode filename parse (FR-4.5) | `parseSeasonEpisodes`, `BE/media/Scanner.kt:72`; `SEASON_EP_RE` (3-digit episode) `:43`, `EP_RANGE_TAIL_RE` `:53`; tests in `ScannerFilenameParsingTest`. No date form, and none is added |
| A null episode number on the Ravilo wire (FR-4.5) | `Episode.episodeNumber: Int`, `shared/src/commonMain/kotlin/dev/jellystructure/shared/tv/Models.kt:794`; `?: 0`, `BE/tv/DetailService.kt:170` |
| TMDB title search for a series with no id (FR-15.5) | scan `BE/media/Scanner.kt:589-590`; re-pull `:1365`. The page-only mark is read at both |
| `tvshow.nfo` with no ids (FR-15.3) | `NfoWriter.buildTvShowXml`, `BE/nfo/NfoWriter.kt:261`, ids only when present `:276-285` |
| The one delete that tells Jellyfin today (FR-16.6) | `FileFixService.runRemove`, `BE/filefix/FileFixService.kt:637-682`; `"Deleted"` at `BE/Main.kt:520-526`; `JellyfinClient.notifyLibraryMediaUpdated`, `BE/auth/JellyfinClient.kt:937` |

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
5. **Backend + admin: mapped numbering** (FR-4.3, decided in Q2).
6. **Image + backend: the yt-dlp reader** (FR-6.3), pinned and checksummed. Q3 means no site depends on it, because the
   model can work out a JSON listing on any site, so it is the last slice: it adds the sites whose pages are built
   in the browser and that yt-dlp already understands.

Each piece files its own QA row when built, with a written plan and no named seat.

**Where the 2026-10-27 amendment lands (DEB-2626)**. It needs no slice of its own.

- **Slice 2** takes these, and its migration carries their columns:
  - the page-only fetcher's engine side: FR-15.1 and FR-15.2, FR-15.3's `series` block and `tvshow.nfo`, FR-15.4,
    FR-15.5's scan mark, FR-15.7's thumbnail, and FR-15.8's proof;
  - FR-4.5's numbering;
  - all of FR-16.
- **Slice 3** takes FR-15.6: page-only episodes never reach the queue, and nothing re-checks them. It also takes
  FR-10.6's screens, drawn by the addendum round of FR-14.4.
- **Slice 4** takes FR-15.9, the page-only investigation.
- **Slice 5** is unchanged. `mapped` is not offered to a page-only fetcher.
