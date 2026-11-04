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
this file disagree, this file wins and the picture is a defect. FR-14 lists the later rounds that draw what round 2
did not.

It amends nothing shipped. Phase 270 gains a job and an FR-270-8 line (its dated amendment is in this file's *Amends*
section and goes into 270 at build). Phase 183 paced TMDB only (its build note); the per-site lane here is new and
modelled on its limiter, which is not changed.

**Amended 2026-10-27 (DEB-2626)**, before any of it was built: a fetcher may be **page-only**, with no TMDB id
(FR-15); a page-only fetcher may number a daily programme **by its air date** (FR-4.5); and every fetcher has a
**retention** setting, *keep the newest N* with a default of 12, which makes FR-16 the one path in this file that
deletes a file. The lines this changes elsewhere point at those FRs.

**Amended 2026-10-27 (DEB-2698)**, after the first fetcher was proven against a live site: a request may be answered by
a redirect to **another host the recipe records**, and the lane then trusts that answer and reads relative references
in it against the **final** URL (FR-2.7).

**Amended 2026-10-29 (DEB-2814)**, after that fetcher's first run: a subtitle rendition whose segments are named
`.webvtt` is read (FR-6.4). 11 of 12 items failed because ffmpeg's HLS demuxer refuses that extension by default.

**Amended 2026-10-29 (DEB-2872, owner decision on DEB-13)**: a subtitle is never a reason an episode does not arrive.
When the source's subtitle rendition cannot be read or muxed, the episode is placed without it (FR-6.4), and only a
video or audio failure counts toward FR-6.5's three failures.

**Amended 2026-11-01 (DEB-2477)**, as *Not on TMDB yet* and the Fetchers tab were built:
- FR-9.1: an episode is also filed when TMDB cannot be reached as it is placed.
- FR-9.3: when a row is first checked, and that a cleared row is checked while FR-8.4's marker is still on.
- FR-8.1: the scan takes the page's words for a TMDB fetcher's episode only for a queued one, and only the fields the marker names.
- FR-9.4 and FR-12: *Pick a different moment* has a route, and the retention preview answers three counts.
- FR-11: a run is an entry on Activity's run list.
- *Build order*: the form's tick and the review state belong to slice 4.

**Amended 2026-11-02 (DEB-2478)**, as the investigation was built (FR-5):
- FR-5.1: `fetch` keeps an inline script's first 1.5 kB, because an item page's media id (`var media = '…'`) lives there; external scripts, styles, SVG and comments go. `probe` may reach a media host the page itself names, never an address or an internal name. The reads are capped at 20, leaving the proof the rest of FR-2.3's 25. Three of the five steps follow what this server saw, not what the model says.
- FR-5.1 and FR-4.2: the model's answer carries **examples**, two to four episodes it read and the number its recipe gives each. The engine compares them with what it read. A recipe the engine cannot stand behind is told back to the model at most twice, inside the 12 turns.
- FR-5.4: the worst case before each turn is *the turns left × (this request's input + one more 60 kB answer) + `max_tokens` each*, at the full rate.
- FR-1.2: a refused key is shown in the failed state (*The key was refused — Settings ▸ AI*); the button is not disabled for it, because only a call can find that out.
- FR-4.1: a recipe the model wrote may record only public host names. An IP address, `localhost`, a name with no dot and the usual internal suffixes are refused.

**Amended 2026-11-02 (DEB-2479)**, as mapped numbering was built (FR-4.3):
- FR-4.3: where the approved table lives, what the model's proposal must satisfy before the admin sees it, what *waiting for a number* is, and what the two routes do.
- FR-10.1, FR-10.2, FR-12: the accept tick carries item ids; the list row's count; the shape of the two routes.

**Amended 2026-11-02 (DEB-2480)**, as the yt-dlp reader was built (FR-6.3, slice 6):
- FR-6.3: yt-dlp is in the image, pinned to one release and checked against its published SHA-256 at the image build; the refusal sentence *this site needs a reader that is not installed yet* is gone. What a `ytdlp` recipe reads, how yt-dlp is held to FR-2's lane, and what it may never reach.
- FR-4.1: the `ytdlp` list and media kinds are defined: which yt-dlp fields a listing reads by default, and that a media `ytdlp` takes an item's link.
- FR-5.1: the model may answer `ytdlp` for a list or a media kind; the proof (FR-4.2) runs it through the same reader, so a site yt-dlp has no extractor for is told back to the model and never stored.

**Amended 2026-11-02 (DEB-3405)**, after QA ran the yt-dlp reader against live sites (DEB-3390). Two of its findings changed what this file promises:
- FR-4.1 and FR-15.8: a flat playlist gives no date. On a YouTube channel, a PeerTube channel, Dailymotion and an Internet Archive item, every `--flat-playlist` entry read `upload_date: null`. FR-4.2's TMDB check, FR-4.5's identity, FR-16.3's order and the air date in FR-8.2 and FR-15.4 all read the published date, so a page-only `date` fetcher could not be saved at all. Now an item with no date in its entry is dated from its own yt-dlp answer: once, when it is new, and stored on its row. The proof dates three items. A run dates at most 20.
- FR-4.1 and *Non-goals*: a recipe cannot rewrite a link, and the Internet Archive is outside the `ytdlp` reader. Its playlist lists `archive.org/download/<id>/<file>` links, which yt-dlp has no extractor for (it reads `/details/`). The proof's refusal of them, *no extractor*, is right.

**Amended 2026-11-02 (DEB-3434)**, after QA's check 7 on DEB-3390 (finding D). A recipe with `subtitle: "eng"` on a YouTube channel placed every episode without its manual English track, and said nothing. The engine asked yt-dlp for `--sub-langs 'eng.*'`. YouTube names that track `en`, so yt-dlp selected nothing and printed `There are no subtitles for the requested languages`. That is not a failure, so FR-6.4's retry never ran and no suffix was written.
- FR-6.3: the recipe still names a 639-2 code, but yt-dlp is now asked for that language under its 639-1 code as well as its 639-2 code, each with an optional region (`en(-.+)?,eng(-.+)?`).
- FR-6.3: when the source has subtitles but none in the recipe's language, the run line now says so. A source with no subtitles at all stays silent, as FR-6.4 already says.

**Amended 2026-11-03 (DEB-3459)**, after QA's check 16 on DEB-3373. A real model ran three times on one TMDB fixture, whose items are titled as TMDB names them and carry no number. Only one run reached the review. One run ended on *maps to no season and episode*, because the model kept a `source` pattern instead of `mapped`. The other ended on *the pattern is not a valid regular expression* in an `item_page` regex. Neither failure could be read afterwards.
- FR-4.1: a named group is written `(?<name>…)`. Python's spelling `(?P<name>…)` is read as that and stored as that. A pattern that does not compile is refused with the engine's own error message, not a bare sentence.
- FR-5.1: the prompt and the schema name the group syntax. The prompt says that items with no number of their own are `mapped` on a TMDB fetcher and `date` on a page-only one. The reason told back for *maps to no season and episode* says the same.
- FR-5.6 (new): every answer of the model's that the engine refuses is written to the server log with the engine's reason.

**Amended 2026-11-03 (DEB-3460)**, after QA on DEB-3373. The open row's recipe header was drawn as `# recipe v1 · <name> · … · written by the model, read-only here`, without a date. The date was stored but never shown, so after *Re-investigate page* and saving, an admin could not tell from the screen whether the recipe had been replaced. The `v1` does not help: it is the schema version, and every recipe has it.
- FR-4.4: the header names the day the recipe was written, after the model: `· written by claude-sonnet-5 on 2026-11-03 ·`. A recipe written by hand has neither. The `v1` stays the schema version and is not a revision count.

**Amended 2026-11-04 (DEB-3566, owner on DEB-799, comment `f54bca69`):** *"make sure that the "Auto Fetcher" tab is not even showed in the case of it being disabled."* The owner opened AI ▸ Fetchers with the Auto-fetch job switched off, pasted a page and could not press *Investigate*. The only reason given was the footer line *Auto-fetch is switched off in Settings ▸ AI*, and he did not see it.
- FR-1.1 and FR-1.2: the tab is present only while a new fetcher could be set up. That needs a key, AI switched on, the Auto-fetch job switched on and `[autofetch] enabled`. A refused key and a spent limit keep the tab, because the owner did not choose them. The *AI is switched off* and *Auto-fetch is switched off* reasons are no longer drawn on the tab.
- FR-1.3: while the tab is hidden, a stopped site's Dashboard row opens Settings ▸ AI and not the hidden tab. On 2026-11-04 Activity ▸ Schedule, where FR-11 lists the fetchers, was not built (DEB-2197). Until it is, a hidden tab leaves the fetchers' runs visible only as entries on Activity ▸ History.
- Acceptance 1 says the same.

**Amended 2026-11-04 (DEB-3569, owner on DEB-799, comment `87543d72`):** *"the github link to file a bug report, is showing very little information. I think it should include more information than this."* The pre-fill was the host, the TMDB id and five words for the steps. It did not carry the failure reason the card shows just above the link.
- FR-10.4: the server writes the report, and it carries the reason, every refused turn's reason, the turns and the site requests, the server version, the model, each step's time, and the last recipe's shape. Every reason goes through a fixed redaction: a quoted span becomes `'<text n>'`, a URL keeps only its host, a path becomes `/…`, a host other than the page's becomes `<host>`, and another program's message is left out. The model's own words, selectors, JSON paths and patterns are never in the report.
- FR-10.4: the TMDB id is no longer sent, because one TMDB lookup turns it into the title. The report says *TMDB series* or *page only* instead.
- FR-10.4: the link drops `template=autofetch.yml`, because no such template exists, and it is cut to fit 8,000 characters.
- Acceptance 19 says the same.

**Amended 2026-11-04 (DEB-3567, owner on DEB-799, comment `a8cf8378`):** *"This series is a news series, so new episodes every day (even multiple times a day)."* The owner set up a page-only fetcher for a broadcaster's news programme. The model's recipe was right: an `html` listing, `ytdlp` media and `date` numbering. Two of our own gaps stopped it. The page prints each item's date with its time and length after it (`3. Nov. 2026 18.30 | 25m`). That was not read as a date, so no item had a number. And with three items on one date, FR-4.5 refused the recipe at save, so a programme that airs more than once a day could never be set up.
- FR-4.5: a date is read the way the page prints it even when a time or other text follows it. That is what FR-4.5 already said, so this part is a fix and not a change.
- FR-4.1 and FR-4.5: a new numbering, `datetime`, for a page-only programme that airs more than once a day. The season is the year and the week, and the episode is the day and the time. 3 November 2026 at 18.30 is `S2644E808`. `date` is unchanged, so no saved fetcher is renumbered.
- FR-5.1, FR-15.8: the model is told when to choose `datetime`. A `date` recipe refused for two items on one date says so to the model.
- *Stand-in data*, *Acceptance* 10 and *Non-goals* say the same.

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

   *Amended 2026-11-04 (DEB-3566):* the tab is present only while a new fetcher could be set up. That means all four of
   these hold: the key is set, AI is switched on (`[ai] enabled`), the Auto-fetch job is switched on
   (`[ai.autofetch] enabled`, its card on Settings ▸ AI), and Auto-fetch itself is on (`[autofetch] enabled`, FR-13).
   When any of them fails, `GET /api/ai/page` leaves `"fetchers"` out of its list. The order of the tabs that remain
   does not change. The server decides this. The admin reads the list when the page opens, and an absent `?tab=fetchers`
   opens the first present tab (`suggestions-as-a-tab-on-the-ai-page.md` FR-5.3). The tab list is read only by the
   admin (`AiPageApi`, the sidebar's probe) and is not on the Ravilo wire.
2. **A switched-off AI, a revoked key or a spent limit does not remove the tab, and does not stop a fetcher.** A saved
   fetcher runs from its recipe with no model call (finding 2). What those states stop is a *new* investigation and
   *Re-investigate page*. Those buttons are shown, disabled, with the reason (`processing-runs-as-nine-features.md`
   FR-11): *AI is switched off in Settings ▸ AI* · *The key was refused — Settings ▸ AI* · *This month's Auto-fetch
   limit is spent ($n of $m)*.

   *Amended 2026-11-04 (DEB-3566):* a **switched-off AI or a switched-off Auto-fetch removes the tab** (FR-1.1). The
   tab is not drawn with a disabled button and a reason. The two switches are choices the owner made, and the reason
   line was easy to miss. **A refused key and a spent limit keep the tab.** The owner did not choose either of them, so
   the tab still shows them: a refused key in the failed state (amendment of 2026-11-02, DEB-2478), and a spent limit
   as *Investigate* and *Re-investigate page* disabled with *This month's Auto-fetch limit is spent ($n of $m)*. The
   reasons *AI is switched off in Settings ▸ AI* and *Auto-fetch is switched off in Settings ▸ AI* stay as the `409`
   answer of the investigation routes, which the tab draws in its failed state if a switch changes while it is open.
   This amendment does not change when a saved fetcher runs. It still runs while AI or the Auto-fetch job is switched
   off, and it does not run while `[autofetch] enabled` is off (FR-13).
3. With no key, the AI page is absent, as drawn (*No AI key*). Saved fetchers keep running; they are listed on
   Activity ▸ Schedule (FR-11), so a fetcher never runs where nobody can see it.

   *Amended 2026-11-04 (DEB-3566):* this applies to a hidden Fetchers tab as well as to an absent AI page. Saved
   fetchers, their recipes, their sites' state and the *Not on TMDB yet* queue are kept unchanged. All of them are on
   the tab again when it returns. While the tab is hidden, a stopped site's Dashboard row (FR-2.5) opens
   **Settings ▸ AI** (`/settings?tab=ai`) and not `/ai?tab=fetchers`, with the path *Settings ▸ AI › Auto-fetch* and
   the action *Settings*. The admin brings the tab back there, then presses *Resume site* on it. The row's sentence is
   unchanged. **On 2026-11-04 Activity ▸ Schedule was not built** (`processing-runs-as-nine-features.md` FR-8, DEB-2197).
   Until it is, a fetcher that runs while its tab is hidden is visible only as its entries on Activity ▸ History (FR-11). This
   is also true today with no key.

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
7. **A redirect to a recorded host (amended 2026-10-27, DEB-2698).** A request the lane makes may be redirected, and
   the lane decides what the answer is worth by the host it came **from**, not the one that was asked.
   - If the final URL's host is the host asked for, or any host in the recipe's `source.hosts` (FR-4.1) or named by its
     templates, the answer is the lane's own: it is judged like any other (FR-2.4, FR-2.5) and its **final URL** travels
     with it.
   - If the final host is not recorded, the answer is discarded (status `310`, no body) and the lane carries on, as
     before. A recorded host is the only trust there is: nothing is added to `source.hosts` by following a redirect.
   - A relative reference inside an answer, such as the variant URIs of an HLS master playlist, is resolved against
     the answer's **final** URL, never the requested one. Every user of that answer does this: the proof's probe
     (FR-4.2) and the download (FR-6.3). A master served from `a.example/redirect/x.smil` that redirects to
     `edge2.example/x.smil/playlist.m3u8` has its variants under `edge2.example/x.smil/`.
   - A CDN that rotates between edges is recorded by all the edges seen; an edge the recipe does not record is refused
     with the `310` above, which the proof reports and *Re-investigate page* can repair.
   - The subtitle rendition's first chunk (FR-6.4) is read through the same final-URL base.

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
     - **`ytdlp` list (2026-11-02, DEB-2480).** The listing is yt-dlp's flat playlist of `source.url` (`--flat-playlist`,
       at most 100 entries), read as JSON like a `json` list: `items_path` defaults to `entries`, and each field is a
       `path` into an entry. A field the recipe leaves out defaults to yt-dlp's own name for it: `title`, `link` ←
       `url`, `id`, `published` ← `upload_date` (`20261102` reads as `2026-11-02`), `description`, `duration`,
       `season` ← `season_number`, `episode` ← `episode_number`, `image` ← `thumbnail`. `next` is not used: yt-dlp
       pages its own playlist. A listing of 100 entries is not read in full (FR-16.5).
     - **An item with no date (amended 2026-11-02, DEB-3405).** A flat entry usually carries no date: on all four
       sites QA tried, `upload_date` was null. So `published`'s default is the first of `upload_date`,
       `release_date` and `timestamp` (its UTC date) that the entry has. An item that has none of them is
       **dated from its own yt-dlp answer**. That is one read of the item's link, the same read FR-4.2's probe makes.
       It asks yt-dlp for those three fields only and never for the whole answer, which runs to megabytes on
       YouTube (DEB-3404). The read takes the same three fields in the same order.
       - It is one paced request in the lane. The link is checked against the recorded hosts and `robots.txt` first,
         exactly as for a download (FR-6.3).
       - The date is stored on the item's row, like `item_page`'s value, and is never read again. An item whose own
         answer has no date either is stored as *no date*, and is not read again until *Re-investigate page*. So a
         run with nothing new still costs the listing alone (FR-2.3).
       - **In the proof** (FR-4.2, FR-15.8), the first three undated items in listing order are dated, and no
         others. The probe's read of an item dates that item too, so the proof makes at most four requests: the
         listing and three item reads. That fits inside what the investigation leaves the proof (FR-2.3, FR-5.1).
         An item the proof did not date is not held against *every item maps to exactly one identity*. It gets its
         number on the run that dates it. Under `date` numbering the proof needs at least two dated items with two
         different dates. Otherwise it fails with *yt-dlp gives these items no date, so they cannot be numbered by
         date*, and that reason is told back to the model like any other.
       - **In a run**, the new undated items are dated in listing order, at most **20** per run. The rest wait for
         the next run, and the run line ends `· n items not dated yet, on the next runs`. An item that has no
         number until it is dated is not downloaded until then. FR-16.3's order counts only dated items, with the
         listing order breaking ties as before.
       - **An item yt-dlp gives no date.** Under `date` numbering it is skipped with `<title>: yt-dlp gives it no
         date`. That is not a failure, and it does not count toward FR-6.5's three. Under `source` or `mapped`
         numbering it is placed as before, with no air date.
       - The date is the calendar date yt-dlp gives, with no time-zone shift. This is FR-4.5's *the way the page
         prints it* for a site that is read through yt-dlp.
       - Rejected: yt-dlp's `approximate_date` extractor argument. It is YouTube-only, and it gives dates like
         *3 weeks ago*, which cannot be an identity. Also rejected: no longer promising date numbering through
         yt-dlp. Five FRs read the published date, and the cost is one read per new item, made once.
     - **No link rewriting (2026-11-02, DEB-3405).** An item's link is handed to yt-dlp exactly as the listing gives
       it. A recipe has no step that rewrites a link, because a rewritten link is a URL the model wrote, and FR-6.3
       only lets yt-dlp be told a link the site listed. A listing whose links yt-dlp has no extractor for is refused at
       the proof with *no extractor*. Such a listing is the Internet Archive's `/download/` file links (*Non-goals*).
   - `media`: how an item becomes a stream — a manifest URL template or a JSON path to it, or `ytdlp`;
     - **`ytdlp` media (2026-11-02, DEB-2480).** The item's own link, which must be on a host the recipe records, is
       handed to yt-dlp (FR-6.3). There is no manifest, no `manifest` template and no `item_page`.
     - **`item_page`** (2026-10-27, DEB-1076). The listing gives an item's link but not its media id, so the engine
       opens the item's own page and reads one value from it. Read either by a CSS selector and attribute, or by one
       rule from `pattern`'s grammar with a single named group `value`, checked the same way for size and
       backtracking. Script text counts as page text.
       - The value must match `[A-Za-z0-9._-]{1,128}`, and it fills the manifest template's `{value}`. Otherwise the
         item is skipped with its line, and nothing is fetched for it.
       - The page is on the same host as the listing, and the request is a page request in FR-2's lane.
       - It is read only for an item that is not already in the library, and its value is stored with the item.
         So a run with nothing new costs the listing request alone.
       - KVF's case: `/sjon/sending/dv` links 12 items, but only the newest one's media id
         (`var media = 'DOV7313'`) is on the listing. Each other id sits only on that item's own page
         (`?sid=214396` → `DOV7302`).
   - `numbering`: `source` (the item's own season and episode numbers, read by a pattern), `mapped` (an
     explicit table, item id → `SxxEyy`, FR-4.3), `date` (the item's published date, page-only fetchers only,
     FR-4.5), or `datetime` (the item's published date and time, page-only fetchers only, FR-4.5's amendment of
     2026-11-04, DEB-3567);
   - `series`, page-only fetchers only (FR-15.3): where the page gives the programme's name, description and image,
     in the same form as `list`'s fields;
   - `retention`: `all`, or the newest `N` with *mirror the page* on or off (FR-16);
   - `pattern`, for `source` numbering: an ordered list of rules from a fixed grammar (*year in the title → season*;
     *"N. partur" or "(N:M)" → episode*; *S{nn}E{nn}*; *a field of the listing → season / episode*). A rule is a regular
     expression with named groups `season` and `episode`, checked by the engine for size and against catastrophic
     backtracking before it is stored;
     - **The group syntax (2026-11-03, DEB-3459).** This rule covers `pattern` and `item_page.pattern`. A named
       group is written `(?<season>…)`, the syntax of the engine's own regular expressions (Kotlin/Native).
       - Python's `(?P<season>…)` names the same group in another spelling. Before any check, the engine rewrites each
         `(?P<` to `(?<`, and stores and shows the recipe in that form. Python's back-reference `(?P=name)` is not
         rewritten. It does not compile, so it is refused like any other pattern that does not compile.
       - A pattern that does not compile is refused as `the pattern is not a valid regular expression: <the engine's
         message>`, at most 160 characters of it. The reason goes back to the model as FR-5.1's correction, so the
         model learns what is wrong.
       - Rejected: refusing `(?P<…>` with a hint. That costs a turn (about $0.05 on Sonnet 5) for a spelling the
         engine can read with no doubt about what was meant.
   - `language`: the spoken language (ISO 639-2) and the subtitle language to look for;
   - `quality`: the highest variant, always.

   Nothing in a recipe is executed. A URL template may only name a host recorded in `source`.
2. **Proven before it is saved.** The model's recipe is accepted only when the engine, running it itself against the
   live site, gets the same answer the model gave (FR-270-4, *nothing trusted blindly*):
   - the listing yields at least two items, and every item has a title and a link;
   - every item maps to exactly one `SxxEyy`, and no two items map to the same one;
   - one manifest, chosen by the engine, resolves to a video variant `ffprobe` can read, with a duration. With `item_page`,
     that manifest's value is read from its item's page during the proof, not taken from the listing;
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
   **Built 2026-11-02 (DEB-2479).**
   - **Where the table lives.** A `mapped` recipe is `numbering: {kind: "mapped"}` and carries no table. Each item's *approved* number is a
     column of its own row (`approved_season`, `approved_episode`), so the recipe never holds a row the admin did not accept. `mapped` is offered
     to a TMDB fetcher only; a page-only fetcher refuses it.
   - **The proposal.** The model's answer carries `mapping`: rows of the item's link, its title as listed, and the season and episode it gives it.
     The engine resolves each row to a listing item (the link first, then the id, then an exact title), and the proof refuses the proposal
     when a row names an episode TMDB does not have, when two items share one, or when no row resolves. The model is told why, at most twice,
     inside the 12 turns. A row that resolves to no item is dropped. The tool `tmdb_series` now also answers each episode's overview.
     A `mapped` proposal is never *proven* (FR-4.2's agreement is for patterns): the admin's tick is what approves it.
   - **The review** lists every listed item. A row with a proposal has an accept tick, all unticked; a row the model left out reads *waiting for
     a number*. *Save* sends the item ids that are ticked, and only those get an approved number. Saving with none ticked is allowed: every
     item then waits.
   - **Waiting for a number.** A run does not download an item that has no number: for `mapped`, one with no approved number; for a `source`
     fetcher that is not proven, one whose number TMDB lacks (FR-4.2) and that the admin has not approved. The run line reads
     `<title>: waiting for a number`, and the fetcher's row reads *n items waiting for a number*. The same flow serves both.
   - **`POST /api/autofetch/{id}/items/{itemId}/number` `{season, episode}`** approves the number the admin typed, or the proposal they accepted.
     It answers `422` for a number below 0 or over 9999, `409` when another item of the fetcher already has that number or the series folder
     already holds a file under it, `404` for an unknown fetcher or item, and `200` with the item otherwise. It writes nothing outside the
     database; the next run downloads the item.
   - **`POST /api/autofetch/{id}/items/{itemId}/propose`** is one model call on the *Auto-fetch* job at the full price, the limit checked before
     it is sent (FR-5.4), asked about that one item against TMDB's episode names and overviews and the numbers already taken. Its answer is
     kept on the item as a *proposal* (`proposed_season`, `proposed_episode`, `proposed_note`) and shown beside the item; nothing is downloaded
     until the admin accepts it through `…/number`. A proposal naming an episode TMDB lacks, or a number another item has, is refused (`422`)
     and kept as nothing. AI off, no key or the limit spent is `409` with the reason, and no request reaches Anthropic.
4. **Stored, versioned, readable.** The recipe is stored with the fetcher, with its schema version, the model and the
   date it was written. The fetcher's open row shows it read-only, as drawn. Its header names the model and that date
   (`written by claude-sonnet-5 on 2026-11-03`; amended 2026-11-03, DEB-3460), so a saved re-investigation shows as a new
   date. The header's `v1` is the schema version. *Re-investigate page* writes a new recipe
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
   - **Amended 2026-11-04 (DEB-3567): a date with something after it.** A page often prints the time and the length
     after the date, as in `4. Nov. 2026 6.00 | 4t 0m`. That reads as 2026-11-04. Every form `isoDate` reads
     (`dd. Month yyyy`, `dd.MM.yyyy`, `dd/MM/yyyy`, `yyyy-MM-dd`) is read when the text after the year is empty or
     starts with whitespace. Text before the date still means it is not a date. Before this, the `dd. Month yyyy` and
     `dd.MM.yyyy` forms refused anything after the year. So *the way the page prints it* failed on such a page, and
     every item read *maps to no season and episode*. This part restores FR-4.5 and changes nothing it promised.
   - **Amended 2026-11-04 (DEB-3567): more than one broadcast a day, `numbering: datetime`.** This is for a page-only
     fetcher of a programme that airs more than once on some dates, such as a news programme. `date` stays as it is,
     for a programme that airs at most once a day. A saved `date` fetcher is not renumbered, because nothing about
     `date` changes.
     - **The time.** It is read from the same published text as the date: the first `H.MM` or `H:MM` after the date
       (hour 0–23, minute 00–59), with or without `kl.` before it. An ISO `2026-11-03T18:30…` gives 18:30. Everything
       after the time is ignored (` | 25m`). The time is the clock time the page prints. No time zone is applied,
       even when the text carries an offset, as for the date.
     - **The number.** Let *d* be the date's day of the year (1–366), as for `date`, and *m* the time's minute of
       the day (0–1439).
       - The season is the last two digits of the year, followed by the week of the year as two digits:
         `(year − 2000) × 100 + ⌊(d − 1) ÷ 7⌋ + 1`. Week 1 is 1–7 January, and week 53 holds 31 December, and
         30 December in a leap year. These are not ISO weeks.
       - The episode is `((d − 1) mod 7) × 140 + ⌊m × 140 ÷ 1440⌋ + 1`, from 1 to 980. Each day of the week has 140
         slots of about ten minutes, 10 min 17 s each.
       - 3 November 2026 is day 307. At 18.30 it is `S2644E808`, and at 21.00 it is `S2644E823`. 4 November at 6.00
         is `S2644E876`.
       - A date outside 2010–2099 gives no number. The season then always has four digits.
     - **Why this shape.** Every one of FR-4.5's reasons for a number still holds. Two more constraints narrow it.
       - `SEASON_EP_RE` reads at most four season digits and three episode digits. A date and a time do not fit
         readably into seven digits, so one of the two numbers has to be packed.
       - The number depends on the item's own date and time and on nothing else. So it is the same on every run, and
         neither retention nor a newly listed item renumbers anything. Seasons sort by week, and episodes sort by day
         and time within the week, so the order is air order.
       - Two items can fall into one slot only if they air less than 10 min 17 s apart. 18.25 and 18.30 share one;
         18.30 and 18.35 do not. A broadcaster does not air the same programme twice that close together.
       - Rejected: one season per month, `S2611`. A month leaves 32 slots a day, so the slots are 45 minutes long,
         and bulletins at 18.00 and 18.30 would share a number.
       - Rejected: one season per day. A season number cannot hold a readable date in four digits. A daily season
         also gives a programme kept in full 365 seasons a year on a TV's season picker.
       - Rejected: a wider `SEASON_EP_RE`, such as `S20261103E1830`. That changes how the whole library's file names
         are read, and Jellyfin reads the file name with its own rules.
       - Rejected: numbering by the item's place among that date's items. That number changes when an earlier item
         turns up late, and it is not stable across runs without being stored.
       - Ravilo shows a code like `S2644E808` and the air date (R148). It does not show the time. Showing the date and
         time in place of the code is still a Ravilo spec of its own (*Non-goals*).
     - **Filename.** Placed per FR-6.6, with the time after the ISO date:
       `<Series>/Season 2644/<Series> - S2644E808 - 2026-11-03 18.30 - <episode name>.mkv`. The time is written
       `HH.MM`, because a colon is not allowed in a file name.
     - **An item with a date and no time.** The proof fails with *'<title>' has a date but no time, so it cannot be
       numbered by date and time*. On a run the item is skipped with `<title>: the page gives it no time`. That is not
       a failure, and it does not count toward FR-6.5's three.
     - **Two items in one slot.** These fail FR-4.2's *no two items map to the same one* at save, with *2 items on
       2026-11-03 (18.25, 18.30) air too close together to get numbers of their own*. On a later run the second is
       skipped with `<title>: airs too close to 2026-11-03 18.30, already placed`.
       The fetcher carries on.
     - **A `ytdlp` listing.** Its published value is a date with no time (FR-4.1's *An item with no date*), so a
       `ytdlp` listing cannot be numbered by `datetime`. The proof fails it with *yt-dlp gives these items no time*.
       Reading a time from yt-dlp's `timestamp` is not in this amendment (*Non-goals*).
     - **The model's examples** (FR-5.1). Under `datetime` the engine compares an example's date with the date it
       read, and it does not compare the season or episode the model gave. The model is not asked to do the
       arithmetic above.
     - **Everywhere else.** Where this file says *`date` numbering*, `datetime` follows the same rule, except for the
       number itself, the filename's time and the two cases above. That covers page-only fetchers only, the
       `published` field the listing needs, FR-8's air date, FR-16.3's order and the queue's exclusions. The fetcher's
       row reads *By date and time*, and its description reads *season = the year and week, episode = the day and
       time*. The review shows the time after each row's date.
     - **Re-investigating a saved fetcher.** If *Re-investigate page* changes a fetcher between `date` and `datetime`,
       the files already placed keep their names and numbers. Only items not yet placed are numbered the new way.

### FR-5 — What the AI does, and what leaves the house

1. **The investigation is one conversation with tools**, on the job's chosen model, using the synchronous Messages
   API. The tools are this server's, not Anthropic's: `fetch(url)`, which this server performs under FR-2 and answers
   with the body trimmed to text and structure (scripts and styles removed, at most 60 kB per answer); `probe(url)`,
   which runs `ffprobe` on a manifest and answers with its variants; and `tmdb_series(id)`, which answers with the
   series' seasons and episode names, numbers and air dates. `fetch` refuses a URL whose host is not the page's site.
   The conversation ends with the recipe, constrained by `output_config.format`. At most **12 turns**; past that it
   stops as *Not a series* with *the page could not be worked out in 12 steps*.
   **`ytdlp` (2026-11-02, DEB-2480).** The recipe's schema also offers `ytdlp` for `list.kind` and `media.kind`, and the
   system prompt tells the model to choose it only for a site whose page is built in the browser and that yt-dlp has an
   extractor for. The model has no tool that runs yt-dlp: the proof (FR-4.2) does, through FR-6.3's reader, and a site
   yt-dlp cannot read ends as the proof's failing check, told back to the model like any other.
   **Items with no number, and the group syntax (2026-11-03, DEB-3459).** The QA fixture had four items titled exactly
   as TMDB names them, each dated on TMDB's air date. On it, one of three real-model runs reached the review.
   - **No number in the item.** `source` numbering needs a season or an episode number in the item itself. The
     system prompt's numbering step now says what to do when the items carry none, only a title (TMDB's own title
     included) or a date. On a TMDB fetcher that is `numbering: mapped` with `mapping`, and on a page-only fetcher it is
     `numbering: date`. The proof refuses a recipe with *'<title>' maps to no season and episode*. That reason, when it
     goes back to the model, now ends with the same advice for the kind of fetcher: *if the items carry no number of
     their own, answer `numbering: mapped` with `mapping`* (TMDB) or *… answer `numbering: date`* (page-only).
   - **The group syntax.** The prompt's *Regexes* line, and the schema's descriptions of `pattern[].regex` and
     `item_page.pattern`, say that a named group is written `(?<value>…)`, not `(?P<value>…)` (FR-4.1).
   - **More than one broadcast a day (2026-11-04, DEB-3567).** The schema's `numbering` offers `datetime`. On a
     page-only fetcher, the numbering step says to answer `numbering: date` for a programme that airs at most once a
     day, and `numbering: datetime` when two items can share a date and the published text carries a time. A `date`
     recipe the proof refuses because items share a date gets that reason with *if the published text gives a time,
     answer `numbering: datetime`* added, so the model can correct it within FR-5.1's two corrections.
   - Rejected: a new numbering that matches an item's title to a TMDB episode name with no model and no tick. That is
     `mapped` without the admin's approval, and FR-4.3 (Q2) is that a mapping fails closed.
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
6. **A refused answer can be read afterwards** (2026-11-03, DEB-3459). The engine refuses an answer that is not in
   the recipe's form, that says `series` and gives no recipe, that fails the validator or the proof (its examples
   included), or that was cut off at `max_tokens`. Each time, the engine writes one `info` line to the server log under `autofetch`. The line has the investigation's
   id, the turn, the engine's reason, and the model's answer text as it came back, cut at 8 kB.
   - A `not_a_series` answer is logged the same way, with the model's own reason.
   - An investigation that reaches the review logs nothing extra, because its recipe is what it stores.
   - The log stays on this server. Nothing in it is sent anywhere, and FR-5.2's *What is sent* does not change.
   - It is not an `ai_transcript` row. That table holds phase 272's batch conversations (FR-272-13), keyed by batch,
     and the AI page reads it as a list of batches. An investigation is not a batch.
   - Rejected for now: showing the refused answers in the failed state. That is a new admin surface and needs a
     design round. This file asks for one when a household admin, not QA, needs to read them.

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
   with the image's own `ffmpeg`, the way the hand scripts do it.
   **yt-dlp (amended 2026-11-02, DEB-2480).** A `ytdlp` recipe reads its listing and downloads its items with yt-dlp,
   which is in the runtime image of the backend (never in a Ravilo image): release 2026.10.26, the `yt-dlp` Python
   zipapp, downloaded at the image build and checked against the SHA-256 that release publishes in `SHA2-256SUMS`. The
   build fails when it does not match, and runs `yt-dlp --version` once. The sentence *this site needs a reader that is
   not installed yet* is gone. A server without the binary (a development machine) answers *yt-dlp is not installed on
   this server* where it would have run it, and nothing else changes.
   - **Held to the lane (FR-2).** yt-dlp is a program making its own requests, so the engine holds it to what the lane
     promises. The listing is one paced page request against `source.url` (checked against `robots.txt` for a general
     crawler, stopped and backed off like any other). An item's link is checked against `robots.txt` before yt-dlp
     opens it. The download runs inside the lane's download slot (one at a time, 60 s between two episodes). yt-dlp is
     started with this server's own User-Agent (FR-2.1), `--sleep-requests 10`, `--ignore-config` and no cache, and
     with its `generic` extractor off (`--use-extractors default,-generic`): only a site yt-dlp has an extractor for is
     read, and a page cannot send it to whatever its markup names. What yt-dlp's last error says is judged like an
     answer: `HTTP Error 429` backs the lane off (FR-2.4), `HTTP Error 401`/`403` on a site that worked before stops it
     (FR-2.5).
   - **No DRM (FR-3.3).** yt-dlp saying *DRM protected*, or an item whose every format is marked DRM, refuses the item
     as FR-3.3 does: *this site protects its videos; Auto-fetch does not get past that*.
   - **Quality.** `-f "bv*+ba/b"`: the best video and the best audio, merged into MKV (`--merge-output-format mkv`); a
     source that serves one combined file is remuxed to MKV (`--remux-video mkv`).
   - **Language at the first mux.** The audio tag is given to yt-dlp's own ffmpeg step, `--postprocessor-args` for
     `Merger` and for `VideoRemuxer`: `-metadata:s:a:0 language=<audio>`. Never after (FR-6.4). The language and the
     duration are then checked by FR-6.5 like any other file.
   - **Subtitles.** `--write-subs --embed-subs`, and `--sub-langs` naming the recipe's subtitle language (`all,-live_chat`
     when the recipe names none). Only the source's own subtitles are asked for, never its automatic captions.
     A subtitle never fails the episode (FR-6.4): when yt-dlp fails and its last output mentions a subtitle, it is run
     once more without them, and the run line ends `· no subtitle: yt-dlp could not embed it: <its last line>`.
     **The language under every code a site uses (amended 2026-11-02, DEB-3434).** The recipe names its subtitle
     language as an ISO 639-2 code, like the audio one (FR-4.1, unchanged). Sites name their tracks by ISO 639-1 code
     instead: YouTube, PeerTube and Dailymotion call English `en`, or `en-GB`. So `--sub-langs` lists the language under
     each form it has, each followed by `(-.+)?` for a region:
     - the recipe's own code;
     - its other 639-2 form, where the bibliographic and terminological codes differ (`ger` and `deu`);
     - its 639-1 form.

     The forms come from `LanguageResolver`'s tables. For `eng` that is `en(-.+)?,eng(-.+)?`, and for `fao` it is
     `fo(-.+)?,fao(-.+)?`. A language with no 639-1 form in those tables is asked for by its 639-2 forms only.
     The region suffix is deliberately not `.*`. yt-dlp matches each entry against the whole code, ignoring case, so
     `fo.*` would also take `fon`, a different language. This was checked against the pinned 2026.10.26 build: `eng.*`
     selects nothing from `en`/`en-GB`, and `fo.*` selects `fon`. Every track that matches is embedded. yt-dlp tags each
     with its own 639-2 form (`en` is embedded as `language=eng`). FR-6.5 does not check that tag.
     **A language the source does not have (amended 2026-11-02, DEB-3434).** The download also writes the source's
     subtitle list to a file in its `.part` folder (`--print-to-file "before_dl:%(subtitles)j" …`). Unlike `--print`,
     this keeps the download's own output and does not make it a simulation. When nothing was embedded:
     - the source lists no subtitles, or the file cannot be read: no suffix, as FR-6.4 says for a source with no
       rendition;
     - the source lists subtitles, but none in the recipe's language: the episode is placed and the run line ends
       `· no subtitle: none in <subtitle>; the source has <codes>`. The codes are the source's own, sorted, at most
       five, then `+<n> more`. Like every subtitle outcome, it is not a failure (FR-6.5) and is not retried.
   - **Work files.** yt-dlp writes only under the work folder (FR-6.6): the output is `<work file>` and its
     intermediate files go to a folder `<work file>.part` beside it, removed when the item ends, whatever the outcome.
   - **What it can never be told.** The command line is built from the item's link, the work path and the two language
     codes, each shell-quoted; a link that is not `http(s)` on a recorded host is skipped with its line and nothing is
     started.
4. **Language and subtitles at the first mux.** The audio track is tagged with the recipe's language during the
   download's own mux, never afterwards. The source's subtitle rendition is looked for on every episode; when one
   exists and its first chunk holds cues, it is embedded with the same language tag, and no sidecar is left behind.
   **Segment extension (amended 2026-10-29, DEB-2814).** A subtitle rendition's segments may be named `.webvtt`, which
   ffmpeg's HLS demuxer refuses by default (`extension vtt mismatches allowed extensions`, then *Invalid data found*).
   The subtitle input, and only that input, is opened with `-extension_picky 0`; the video and audio inputs keep
   ffmpeg's extension check. Proven against ffmpeg 5.1.9, the image's own build.
   **A subtitle never fails the episode (amended 2026-10-29, DEB-2872; owner on DEB-13: *"If no subtitles are available
   (which will be a normal scenario) then it will still be downloaded, just without the subtitles"*).** When the
   source has no subtitle rendition, the episode is placed without one, as before. So it is when the rendition is
   advertised but its first chunk holds no cues or cannot be read, and when ffmpeg fails with the subtitle input in the
   mux: the mux is run **once more without the subtitle input**, and the file that comes out is the episode. The run
   line for a placed episode then ends `· no subtitle: <reason>` (`the first chunk holds no cues`, or
   `ffmpeg could not mux it: <ffmpeg's last line>`); an episode whose source simply has no rendition has no such
   suffix. Only if the mux without the subtitle also fails is the item failed, and that is a video or audio failure
   (FR-6.5). Nothing retries the subtitle later.
5. **Checked before it is placed.** `ffprobe` shows the tagged language and a duration within 10 % of the listing's,
   where the listing gives one; `mkvinfo -v` shows the first top-level `Tracks` before the first `Cluster`. A file that
   fails is deleted from the temporary directory and the run line says why; it is retried on the next run, and after
   three failures the item is marked *failed* until *Re-investigate page*.
   The check never asks for a subtitle stream, and a missing or unusable subtitle (FR-6.4) is not a failure here:
   only the video, the audio, the language, the duration and `Tracks` count toward the three.
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
   *Amended 2026-11-01 (DEB-2477):* for a TMDB fetcher the scan therefore reads the page's words only for an episode that is, or
   was, in the queue, and only for the fields FR-8.4's marker names. An episode placed because TMDB already had it never takes the
   page's text, even where TMDB leaves a field blank. A page-only fetcher's episodes always take it (FR-15.4).
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
   *Amended 2026-11-01 (DEB-2477):* when TMDB cannot be reached as the episode is placed, it is filed anyway. That is not a claim
   that TMDB lacks it: a row is only ever what the last look found, and the daily check (FR-9.3) clears any that TMDB turns out
   to have. Guessing it is there would leave a page-only-style episode with no queue row and no page words behind it.
2. **What it holds**, as drawn: series and `SxxEyy`, the page's own label for it, when it was downloaded, the runtime,
   the page's episode name and overview with where each came from, the air date, and three stills.
3. **How it clears.** Every queued episode's season is re-checked on TMDB **daily**, whatever the release-age interval
   of *Metadata & artwork* says. That check asks TMDB explicitly. A scan does not ask TMDB about an episode that already
   has a title and an overview, so a scan alone would never clear a row that carries page text. When TMDB returns the episode, the row clears itself and the episode is ordinary
   (FR-8.4). *Mark as submitted* is the admin's note that they have entered it on TMDB; the row stays, marked, until TMDB
   returns it. Nothing is ever sent to TMDB: TMDB takes no submissions by API, and phase 307's rule holds anyway.
   *Amended 2026-11-01 (DEB-2477):* a row filed at placement counts as looked at, so its first daily check is a day later. A row
   that TMDB has returned is *cleared* and leaves the list, but it is checked every day for as long as FR-8.4's marker holds a
   field, because a freshly added TMDB episode often has a number and no name yet, and the scan will not ask TMDB about an
   episode that already has a title and an overview. When the check finds the episode, each marked field whose text is no longer
   the page's own words is TMDB's, and its marker goes. A row whose file was moved or deleted by hand is not asked about.
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
4. **The bug-report link** (*File a bug report on GitHub*) is kept on the failed state, and a stopped investigation
   still has none. *Amended 2026-11-04 (DEB-3569).* Until then it was pre-filled with the URL's host, the TMDB id and
   the five steps' outcomes, and nothing else. The owner found that too little to act on (DEB-799, comment `87543d72`).
   The repository is public, so the report is public. What it may carry is therefore set here by rule, never by
   judgement, and a title is a title whatever kind of programme it names.
   - **Who writes it.** The server writes the issue's title and body and pushes them on the investigation
     (`InvestigationView.report`, `{title, body}`, present on a `failed` investigation only). Only the server holds
     the turns, the refused answers and the last recipe. The admin URL-encodes the two into
     `https://github.com/jeggy/jellystructure/issues/new?title=…&body=…` and adds nothing. `template=autofetch.yml` is
     dropped. There is no such file in `.github/`, and if one were added as an issue form, GitHub would ignore `body`.
   - **The title.** `Auto-fetch: could not set up <host>`, then ` · <step>` when a step failed, where `<step>` is that
     step's key (`page`, `videos`, `series`, `pattern`, `script`).
   - **The body**, in this order:
     1. `Host:` the page URL's host. Never its path, query or fragment.
     2. `Fetcher: TMDB series` or `Fetcher: page only`. **The TMDB id is no longer sent.** One lookup on TMDB turns it
        into the title, so it names what the household wants as surely as the title does.
     3. `Server:` the running server's version (`ServerVersion.current`, such as `v1.62-54-g8bb7b638`).
     4. `Model:` the Auto-fetch job's model id and effort.
     5. `Ended:` `not a series`, `limit reached` or `failed`, then `· turns: t of 12 · site requests: n of 20 · cost: $x`.
     6. `Reason:` the failed state's reason, redacted as below.
     7. `Steps:` one line per step: its key, its state and its time where it has one (`pattern: fail · 3.1s`). A step's
        text is never sent, because the *series* step's text names TMDB's title.
     8. `Recipe (shape only):` the last recipe the model answered, when one parsed. It lists:
        - the list kind, and the names of the fields it sets, from FR-4.1's fixed set (`title, link, published`);
        - whether it reads a next page;
        - the media kind, and `item_page` by selector or by pattern, when it has one;
        - the numbering kind and the number of pattern rules;
        - whether it has a `series` block;
        - the audio and subtitle language codes;
        - how many hosts it records besides the page's.

        With no recipe, the line reads `Recipe: none`.
     9. `Proof:` the counts from the last proof the engine ran: items listed, items numbered, TMDB episodes checked
        and TMDB episodes agreeing. The line is left out when no proof ran.
     10. `Refused turns:` one line for each answer FR-5.6 logs, in turn order, as `turn 5: <the engine's reason,
         redacted>`. A `not_a_series` answer reads `turn 7: the model answered not_a_series (its reason quotes the page
         and is left out)`.
     11. A last line: `Titles, the page's content, the model's answers and the URL's path are left out by rule. The
         server's log holds the full answers under autofetch, investigation <id>.`
   - **Never in it:**
     - a title of any kind: an item's, the programme's, TMDB's;
     - an item's link, or the page URL's path, query or fragment;
     - any text the page served, and any text the model wrote;
     - a CSS selector, a JSON path, a regular expression or a URL template;
     - a host other than the page's;
     - a file name or a library path.

     The selectors, JSON paths and patterns are the site's markup, but the model writes them, and on the sites seen so
     far they carry the programme's slug: KVF's listing is `/sjon/sending/dv` (FR-4.1). Without the page they select
     from, a developer could not check them anyway.
   - **The redaction.** Every reason in the body goes through these steps, in this order:
     1. A reason in the model's own words is never shown. A `not_a_series` reason, which the failed state shows as
        the model wrote it, is replaced as in item 10, and so is the `Reason:` line when that is how it ended.
     2. Every span in single quotes becomes `'<text n>'`. It is numbered by first appearance in the body, so the
        same title reads the same everywhere in it. The engine quotes every title, link and unknown kind it names
        this way: `'<text 1>' maps to no season and episode`, and `on '<text 2>' you said episode 3, and the engine
        reads S01E04`.
     3. Every `http://` or `https://` URL becomes its host followed by `/…`.
     4. Every other word that starts with `/` becomes `/…`, so `this site's robots.txt does not allow /…`.
     5. Every word that reads as a host name and is not the page's host becomes `<host>`. A host name here is two or
        more labels of letters, digits and hyphens, joined by dots, and ending in a label of two or more letters. This
        covers `the recipe records <host>, which is not a public host name`.
     6. When a reason ends in another program's message after a colon, that message becomes `<message left out>`.
        The reasons that carry one today are:
        - `the pattern is not a valid regular expression:` (its message quotes the pattern);
        - `the request failed:`;
        - `Anthropic could not be reached:`;
        - a failure carrying yt-dlp's, ffmpeg's or ffprobe's own output (`LaneOutcome.Failed` from
          `programFailure`; yt-dlp's message quotes the link).

        A new reason that carries another program's text must mark where that text starts, so this step never has
        to guess. Each redacted reason is then cut at 300 characters.
   - **Length.** GitHub refuses a new-issue link much over 8 kB. So the link, encoded the way the admin encodes it
     (`afUrlEncode`), is at most 8,000 characters, and the server cuts the body to fit. It drops the refused turns
     first, oldest first, and leaves `(n earlier refused turns left out)`. Then it drops the *Proof* and *Recipe*
     lines. The title and items 1 to 7 are never cut.
   - **The note beside the link** reads *The report is pre-filled with how the investigation went: the host, the
     steps, the turns and the recipe's shape. Never a title, the page's content or the URL's path.* It is the same
     line, reworded, so no new surface and no design round.
   - **The admin still reads it first.** GitHub opens the issue form with the body in it, and the admin can read or
     edit it before submitting. That is a second look, not the rule: the body is clean without it.
   - Rejected:
     - the model's last answer, even cut short, because it quotes the page;
     - the selectors, JSON paths and patterns, for the reason above;
     - recognising a title to redact it, from a list of the household's titles or from TMDB's name. Recognition
       misses a title it has not seen, while the quotes are in the engine's own sentences;
     - keeping the TMDB id because a developer could open the series on TMDB. That is exactly why it identifies it.
5. The drawn *Investigating* spinner is an infinite animation, which the screenshot fence forbids (round 2's
   `MANIFEST.md`, *Open at promotion*); the promotion replaces it (FR-14).
6. **The page-only choice and retention** (amended 2026-10-27, DEB-2626; drawn by FR-14.4's round,
   `design/incoming/2026-10-27-auto-fetch-retention/`):
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
     - On the review state only, one line under that hint says why saving is safe (added 2026-10-27, DEB-2631):
       *Nothing is deleted when you save: retention only ever removes episodes this fetcher has placed itself, and
       it has placed none yet.* A fetcher being reviewed has placed nothing, so FR-16.2 leaves it nothing to delete.
       The open row has no such line; it has the preview instead.
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

*Added 2026-10-27 (DEB-2631):* Activity ▸ History is `processing-runs-as-nine-features.md` FR-8.4's list of past
runs, drawn as `act-hist` in that file's processing-features round. A fetcher's run is one row there, in that
list's own components, and opens its log the way every other row does. It needs no surface or round of its own.

*Added 2026-11-01 (DEB-2477):* a run is recorded as an entry on Activity's run list (scope `auto-fetch`), with its lines logged
under it: what was placed, queued, deleted by retention and refused, in the words of the fetcher's own run record.

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
- *Added 2026-11-01 (DEB-2477):* `POST /api/autofetch/queue/{episodeId}/still/{n}` `{at}` cuts still `n` again at `at` seconds
  (FR-9.4's *Pick a different moment*), and answers `422` when no frame can be cut there.
  `GET /api/autofetch` also carries the queue (not the cleared rows) and, on each site, how many fetchers it stops. The retention
  preview answers `{deletes, keeps, onPage}`: what the next run would delete, how many of the listing's items it keeps, and how
  many the page lists. The screen words those counts and adds none of its own (FR-16.1).

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
   - every item maps to exactly one identity, and no two items to the same one. For a `ytdlp` listing whose entries
     carry no date, this holds for the items the proof dated (FR-4.1's *An item with no date*, amended 2026-11-02,
     DEB-3405);
   - one manifest probes;
   - the `series` block yields a non-empty name.

   The TMDB agreement check is skipped. Q1's *proven* has nothing to decide, because nothing goes to the queue.
   `mapped` numbering is not offered, because it maps onto TMDB's episodes. The numbering is `source` or `date`,
   or `datetime` for a programme that airs more than once a day (FR-4.5, amended 2026-11-04, DEB-3567).
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
     viewer of that series notices. When the admin chooses *The newest* on a TMDB fetcher, *leaves the page* starts
     **off** (added 2026-10-27, DEB-2631): a TMDB series' older episodes scrolling off its page is not evidence the
     household is done with them.
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
2026-10-27, and generic episode headings `Dagbók · 16. oktober` and so on (added 2026-10-27, DEB-2626). For a
programme that airs more than once a day (`datetime`, FR-4.5), use `Kvøldbók` at `varpid.example.fo/sendingar/kvoldbok`.
It has no TMDB id, and its published text is printed as `3. Nov. 2026 18.30 | 25m`. Its listing has three items on
2026-11-03 (6.00, 18.30, 21.00) and one each on 2026-11-02 and 2026-11-05 (added 2026-11-04, DEB-3567). Tests and any
new picture use only these. No real broadcaster page, title, slug or
TMDB id appears in this repository; a test that needs a live-looking listing uses a fixture served by the test itself.

## Acceptance

1. With a key set, the AI page shows *Support · Fetchers · Suggestions*. With AI switched off, *Investigate* is
   disabled with its reason and a saved fetcher still runs.

   *Amended 2026-11-04 (DEB-3566):* with a key set, AI on and Auto-fetch on (both switches and `[autofetch] enabled`),
   the AI page shows *Support · Fetchers · Suggestions*. With AI switched off, with the Auto-fetch job switched off, or
   with `[autofetch] enabled = false`, `GET /api/ai/page` answers *Support · Suggestions* (or *Support* alone without
   Seerr). The AI page draws no Fetchers tab, `/ai?tab=fetchers` opens Support, and a saved fetcher still runs while AI
   or the job is off. With the limit spent, the tab is there and *Investigate* is disabled with its reason. While the
   tab is hidden, a stopped site's Dashboard row opens Settings ▸ AI.
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

    Added 2026-11-04 (DEB-3567):
    - `isoDate` reads `4. Nov. 2026 6.00 | 4t 0m` as 2026-11-04, and `3. Nov. 2026 21.00 | 25m` and
      `3. Nov. 2026 18.30 | 25m` as 2026-11-03. It still reads `27. oktober 2026`, `27.10.2026`, `2026-10-27` and
      `20261027` as before, and it reads nothing from `Sent 27. oktober 2026`.
    - Under `datetime`, those three lines give `S2644E876`, `S2644E823` and `S2644E808`. 1 January 2026 at 0.00 is
      `S2601E001`, and 31 December 2026 at 23.59 is `S2653E140`.
    - `Kvøldbók`'s listing saves under `datetime`, and its five items are placed under `Season 2644` and `Season 2645`
      as `Kvøldbók - S2644E808 - 2026-11-03 18.30 - …mkv` and so on. The Ravilo series detail lists them in air order
      with five different codes. A second run places nothing new and renames nothing.
    - The same listing under `date` fails the proof with *3 items share the date 2026-11-03*, and the reason told back
      to the model names `datetime`.
    - Items at 18.25 and 18.30 on one date fail the proof under `datetime`. An item with a date and no time fails it
      too.
    - `parseSeasonEpisodes` reads `S2644E808 - 2026-11-03 18.30 - …` as season 2644, episode 808, and no range.
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
15. **An item's media id is on its own page** (`item_page`).
    - The stand-in listing carries only links, and each item page carries `var media = '…'`.
    - The proof reads one item page and plays its manifest.
    - The first run reads one page per new item, 10 s apart, and places them.
    - The second run reads the listing only.
    - An item page whose value fails the character rule is skipped with its line, and no request is made for it.

Added 2026-11-02 (DEB-3405), against a stubbed yt-dlp whose flat entries carry no `upload_date`, `release_date` or
`timestamp`, and whose item answers do:

16. **A `ytdlp` listing with no dates.**
    - A page-only `date` fetcher over 5 entries is saved. Its proof makes four yt-dlp calls: the listing and three
      item reads. Each item read asks for the date fields only.
    - The first run dates the other two entries and places all five.
    - The second run calls yt-dlp for the listing only.
    - A listing of 25 new entries is dated 20 in its first run, and the run line ends
      `· 5 items not dated yet, on the next runs`.
    - An entry whose item answer has no date is skipped with `yt-dlp gives it no date`. It is not read again on the
      next run, and it never counts as a failure.
    - When every item answer lacks a date, the proof fails with *yt-dlp gives these items no date*, and nothing is
      written.

Added 2026-11-02 (DEB-3434):

17. **A `ytdlp` subtitle under a site's own code.**
    - Unit: the `--sub-langs` value for `eng` is `en(-.+)?,eng(-.+)?`, for `ger` it carries `de`, `ger` and `deu`, and
      for a recipe with no subtitle it is `all,-live_chat`.
    - Unit: a stubbed download whose subtitle list holds `de` and `fr`, for a recipe asking `eng`, is placed with
      `· no subtitle: none in eng; the source has de, fr`. With an empty list, or none written, the line has no suffix.
    - Live, DEB-3390 check 7 on the NASA channel with `subtitle: "eng"`: `ffprobe` on a placed episode shows a subtitle
      stream tagged `eng`, and its run line has no `no subtitle:` suffix.

Added 2026-11-03 (DEB-3459):

18. **An investigation on items with no number.**
    - Unit: the validator accepts the `item_page` pattern `var media = '(?P<value>[A-Z0-9]+)'`, and the stored recipe reads
      `(?<value>[A-Z0-9]+)`. `(?P=value)` is refused. A pattern that does not compile is refused with
      `the pattern is not a valid regular expression: ` followed by the engine's message.
    - Unit, with a scripted model: on a TMDB fetcher, a `source` recipe whose rules number no item is told back with
      the `numbering: mapped` advice. On a page-only fetcher the advice is `numbering: date`.
    - Unit: the system prompt's numbering step names `mapped` (TMDB) or `date` (page-only) for items with no number.
      The prompt's *Regexes* line and both schema descriptions carry `(?<value>…)`.
    - Unit: each kind of refusal, and a `not_a_series` answer, writes its `autofetch` log line with the reason
      and the answer. An answer over 8 kB is cut.
    - Live, DEB-3373 check 16 again, three runs on the same fixture and settings: each run reaches the review with
      `mapped` numbering and 4 of 4 items proposed. A run that fails is filed with its log line.
19. **The bug report (FR-10.4, DEB-3569).** A test fixture plants one sentinel word in every string that comes from
    outside the engine: each item's title and link, the page URL's path and query, the programme's name, TMDB's
    series and episode names, the model's `not_a_series` reason and its whole answer text, every selector, JSON path,
    pattern and URL template in its recipe, a host it records, and the messages of the regular-expression engine,
    yt-dlp and the HTTP client.
    - The fixture drives each refusal the engine has to a failed investigation: *maps to no season and episode*, the
      example mismatch, two items on one number, a pattern that does not compile, a robots.txt refusal, a non-public
      host, a yt-dlp failure, *not in the recipe's form*, `not_a_series`, *the page could not be worked out in 12
      steps* and *limit reached*.
    - On each one, `report.title` and `report.body` hold no sentinel and no TMDB id. They name the page's host, the
      server version, the model, the turns, the failing step, the redacted reason and one line for each refused turn.
    - A body with enough refused turns to pass the limit gives a link of at most 8,000 characters. It ends its turns
      with `(n earlier refused turns left out)` and keeps items 1 to 7.
    - The link starts `https://github.com/jeggy/jellystructure/issues/new?title=` and carries no `template=`.
    - A stopped investigation has no `report`, and the card draws no link.

## Non-goals

- Films and one-off clips (FR-3.1), and anything behind a login or DRM (FR-3.3).
- Writing to TMDB, ever. *Mark as submitted* is a note.
- Replacing the household's hand scripts. They stay, outside this repository.
- A model call on a schedule.
- Turning a page-only fetcher into a TMDB one, or the reverse. A new fetcher on the same folder skips what is already
  there (FR-6.1), but it does not take over the old fetcher's placed rows, so its retention does not count them
  (FR-16.2).
- Deleting anything a fetcher did not place, and any retention outside Auto-fetch.
- Showing the air date in place of `SxxEyy` on a Ravilo client (FR-4.5), or the air time (FR-4.5's `datetime`).
- A season name such as *Week of 2 November* for a `datetime` season (2026-11-04, DEB-3567). Ravilo shows
  `Season 2644`.
- Reading an item's time from yt-dlp's `timestamp`, so that a `ytdlp` listing can be numbered by `datetime`
  (2026-11-04, DEB-3567).
- **The Internet Archive through the `ytdlp` reader, and any rewriting of a link** (2026-11-02, DEB-3405). An Internet
  Archive item lists `archive.org/download/<id>/<file>` links. They are plain files, and yt-dlp has no extractor for
  them. The generic extractor stays off (FR-6.3), and a recipe cannot rewrite a link (FR-4.1). No site the household
  wants is there: it came up only as a public-domain source for QA. A plain-file media kind, an `.mp4` link that ffmpeg
  reads directly, is not in this file. A site that needs one gets its own amendment.

## Amends

- **Phase 270**, at build, a dated amendment: FR-270-1 gains the *Auto-fetch* card; FR-270-8 gains FR-5.2's line;
  FR-270-6's check runs per turn for this job, which is not a batch (FR-5.4).
- **`suggestions-as-a-tab-on-the-ai-page.md` FR-2**: `"fetchers"` joins the tab list (FR-1.1). *Amended 2026-11-04
  (DEB-3566):* it is in the list only while AI and Auto-fetch are on. That file has a dated line saying so.

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
| ffmpeg, ffprobe, mkvtoolnix | in the runtime image (`Dockerfile:131-133`); runners `BE/media/FfmpegRunner.kt`, `FfprobeRunner.kt`, under `BE/ops/ProcessGate.kt`. yt-dlp (DEB-2480): `Dockerfile`, runtime stage; `BE/autofetch/YtDlp.kt` |
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
  FR-10.6's screens, drawn by the addendum round of FR-14.4 *(amended 2026-11-01, DEB-2477: the open row's **Keep** with the
  server's preview, the deletion lines on the run record and the list row's retention note. The form's tick, the page-only review
  state and the review state's **Keep** are the screens of the investigation, which does not exist until slice 4, so they are
  built there with it)*.
- **Slice 4** takes FR-15.9, the page-only investigation.
- **Slice 5** is unchanged. `mapped` is not offered to a page-only fetcher.
- **`item_page`** (FR-4.1, added the same day for DEB-1076) is **slice 2's**: it is part of the recipe and the `html`
  reader, and Dagur og vika cannot be written without it.
