# How a design becomes code

Two lanes, one set of invariants. Written 2026-10-12 against the state the design project was left
in that day, after the "updated designs" sync was retired.

- **Lane B — the designer briefs the tool.** Works today. A task assigned to the owner carries one
  paste-safe block; he pastes it into the design tool; the result comes back the same way.
- **Lane A — the owner designs, the designer takes over.** Needs one owner decision (below) before
  the *files* can come home; everything after the files land is already defined.

`STATUS.md` holds phase status and nothing here does. [`specs/constitution.md`](../specs/constitution.md)
wins on conflict.

## What is true today

Verified 2026-10-12, not recalled:

- **The design project has no GitHub connection.** The owner removed it. It cannot pull `specs/`,
  it cannot export over the repo, and no commit in this repository comes from it any more.
- **`DesignSync` does not work from this host.** It answers: *"needs design-system authorization, and
  /design-login cannot run in this non-interactive session"*. It can only be enabled by the owner
  running `/design-login` once from an interactive Claude Code session on this machine; headless and
  SDK runs then reuse that authorization.
- **A share link is a viewer, not a transport.** An unauthenticated fetch of a
  `claude.ai/design/p/<id>?…&via=share` URL returns **403**. The link lets a signed-in person look;
  it gives this repository nothing.
- **`design/app/` CSS is production.** `syncDesignAssets` in `build.gradle.kts` copies
  `wf.css app.css detail.css metadata.css seeding.css seeding.js segments.css music.css
  suggestions.css dashboard.css versions.css editions.css`, `fonts/**`, plus `design/flags.css` and
  `design/flags/**`, verbatim into the served admin frontend. A file landing there ships. A **new**
  CSS file must be added to both that copy list and the `<link>` set in
  `src/wasmJsMain/resources/index.html`, or the page ships with no styling at all.
- **`design/ravilo/` is reference only.** No Gradle task consumes it. A Ravilo design round is a
  reference that Páll reimplements in Compose — it never ships as-is. The two halves of `design/`
  therefore carry very different risk, and promotion rules differ accordingly.
- **The fences exist because this went wrong nineteen times.** Every "updated designs" sync between
  2026-06 and 2026-10-12 was capable of reverting hand-authored fixes; several reintroduced real
  infrastructure values into a public repo. `scripts/check-mobile-css.sh`,
  `scripts/check-css-scoping.sh`, `scripts/check-deanonymization.sh` and `scripts/check-phases.sh`
  are the detectors that made those recurrences cheap. They are not optional on an incoming round.
- **[`design/github.md`](github.md) is a frozen receipt of the retired sync.** It describes a
  two-way pipeline that no longer exists and spells out phase numbers that are long since taken.

## Lane B — the designer briefs the tool

In use today. Seven steps, and the only machine in the loop is the owner's clipboard.

1. **Rannvá writes the brief.** One Paperclip task, assigned to the owner, whose body is a single
   self-contained fenced block: no attachments, no pointers into the repo, no file paths the tool
   cannot see, spec context as **excerpts only**, and **never a phase number**.
2. **The brief asks for verifiable facts back**, not a status. "Report the first and last line of
   each file you wrote, and the list of file names" is checkable; "done" is not.
3. **The owner pastes it.** Nothing is "sent" until he has. A round is never reported as out on the
   strength of the task existing.
4. **The tool draws the round** inside the design project.
5. **The files come home** by the lane A transport, once that is settled.
6. **Rannvá reviews the round** against the brief and against what is already shipped, and writes
   the spec — a new numbered file, or a dated amendment to the phase that already owns the
   behaviour. The number comes from `STATUS.md` and the board, as a query.
7. **Tróndur routes the build.** Admin and backend to Sjúrður, the Ravilo clients to Páll, the test
   plan onto the QA queue without naming a seat.

## Lane A — the owner designs, the designer takes over

Everything from step 3 onward is the same as lane B. The open part is steps 1–2: getting the files
out of the design project and into git.

1. **The owner designs** in the tool, by himself, in whatever shape he likes.
2. **The files come home.** Three transports are possible; see the open decisions.
3. **They land quarantined** — proposed: `design/incoming/<yyyy-mm-dd>-<slug>/`, exactly as
   received, in one commit that changes nothing else. Nothing under `design/app/` or
   `design/ravilo/` moves in the same commit.
4. **Rannvá reads the round** and says what it actually changes: which screens, which components,
   what is new behaviour versus new paint.
5. **Promotion is a separate, reviewed commit.** For an admin round, promoting a file into
   `design/app/` is a production CSS change: all four fences must be green, and any hand-authored
   fix the incoming file would drop has to be merged back in rather than chosen between. For a
   Ravilo round, nothing is promoted — the round stays the reference Páll builds against.
6. **Spec, then number, then build**, as lane B steps 6–7.

## The invariants

These hold in both lanes and are not a matter of taste:

1. **Nothing outside this repository ever writes this repository.** A person commits, having looked
   at the diff. That single rule is what retired the nineteen-incident class.
2. **Incoming files are quarantined before they are promoted.** Receiving and promoting are two
   commits, because the first is bookkeeping and the second is a production change.
3. **Spec before Kotlin, bug fixes included.** A design round that changes behaviour gets a
   numbered spec; a fix to shipped behaviour gets a dated amendment inside that phase's spec.
4. **Phase numbers do not leave the repo.** The design project is given excerpts and never a
   number, because the only authority on a free number is `STATUS.md` and the board. Three October
   renumber collisions all started with a number read out of prose.
5. **`STATUS.md` and `specs/` are never mirrored out.** They were deleted from the design project on
   2026-10-12 and nothing by either name goes back.
6. **The repository is public.** No library title, no real host, no address, no key — in a mockup,
   in a spec, in a commit message. `scripts/check-deanonymization.sh` runs over anything that
   arrives from outside, every time.
7. **A hand-authored fix in a design-managed file gets a line in the fence script** that would
   notice it going missing. An unfenced fix is one round away from vanishing.
8. **The wire stays backwards compatible.** A design round that implies a new field adds one; it
   never retypes or deletes a field an installed client reads.

## Open decisions

Four questions are with the owner on **DEB-776**. Until they are answered: lane B runs as described,
and lane A's transport is whatever the owner hands over by attachment. This section is updated with
his answers, and the rest of the document with it.
