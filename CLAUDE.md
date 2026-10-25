# CLAUDE.md

Orientation for anyone — human or agent — working in this repository.

Deliberately short. The long-lived detail lives in files that are maintained for their own sake:
[`README.md`](README.md) for what the product is and how to build it, [`specs/`](specs/) for
requirements, and the board for status. **This file holds only the rules that are not obvious
from the code, and no facts that go stale.** If you are tempted to add a status or a count here, it
belongs on the work's board task.

## What this is

A Kotlin/Native (`linuxX64`) backend plus a Kotlin/Wasm admin frontend that own the metadata for a
Jellyfin library from outside Jellyfin, and **Ravilo**, the Compose Multiplatform client family for
the same library (Android TV, phone, browser, macOS, Linux, Chromecast receiver, Tizen).
[`README.md`](README.md) has the product description, the requirements, the config shape, the
repository layout and the build commands. Read it first.

## The rules that matter

**The task is the identity — new work has no phase number** (owner, DEB-799, 2026-10-15). A piece
of work is one Paperclip task, and its `DEB-n` is the only id it gets: Paperclip hands those out, so
nobody allocates anything and nothing can collide. The owner's ask *is* the work's task — there is no
companion "phase task" beside it, and no `phase:*` label on new work. The labels `ravilo-client` and
`jellystructure-admin` say which tree it lives in, both when it spans. Its spec is a `<slug>.md` in
that tree with `Task: DEB-n` on the line under the title; its FRs are numbered inside the spec
(`FR-1`, `FR-2`, …) and commits cite `(DEB-n, FR-k)`. The legacy numbered phases keep their numbers,
frozen at 321 and R388 — commits, FR ids and the constitution cite them, and an amendment to one
stays in its file. A file, commit or row that mints a new number is the defect to raise, not a
number to reconcile.

**Every change in behaviour starts as a spec.** A new behaviour gets a new `<slug>.md` under
[`specs/requirements/`](specs/requirements/) (backend/admin) or
[`specs/ravilo/requirements/`](specs/ravilo/requirements/) (clients). A fix that changes what a
shipped spec promises gets a **dated amendment inside that spec's existing file**, written before
the Kotlin. A fix that only makes the code do what the spec already says needs no amendment: the
commit names the FR it restores (owner decision, 2026-10-15).
The work's board task is the only place a status lives — not the spec body, not a comment.

**A spec body that still carries a `## Status` heading is fixed the next time that spec is touched,
never in a sweep.** Roughly half the spec tree predates the rule above. When you open one of those
files for any other reason, give it the R384 treatment in the same commit (see `9ce47099`): drop the
status sentence, keep the durable facts — what the phase touches, what it supersedes, who approved
what — under a `## Scope` heading. Those blocks are not purely status, so it is a judgement call per
file and never a `sed`, and a tree-wide commit in a public repository is unreviewable.
`scripts/check-phases.sh` deliberately does not check for this: a guard that fails on files nobody
can fix in one pass is a guard nobody keeps green, and that is worse than no guard. If it is ever
enforced mechanically, that comes after the count has drained, not before.

**[`specs/constitution.md`](specs/constitution.md) wins on conflict.** A change that contradicts it
goes to the owner as a question; it does not quietly go into the file.

**This repository is public, and prod serves users beyond the household.** Never commit a library
title, a filename from the library, or anything else that identifies what the household owns —
`scripts/check-deanonymization.sh` exists because this has gone wrong. Treat anything a viewer would
notice as a break as an owner decision, not a judgement call.

**The wire is backwards compatible.** Installed clients are not upgraded in lockstep with the
backend; an old APK will call today's server. Never delete or retype a field an existing client
reads — add, deprecate, and remove a release later.

**`design/app/wf.css` and `app.css` are production CSS**, copied verbatim into the distribution by
Gradle's `syncDesignAssets`. They are not mockup CSS awaiting reimplementation: a new component class
is added there and that is the whole styling step. No Tailwind, no PostCSS. The admin frontend drives
the DOM through `kotlinx.browser` and renders **server-pushed state only** — no derived or optimistic
state in the frontend.

**Design arrives by a person, never by a pipeline.** Nothing outside this repository writes this
repository: a design round is drawn, read and committed by someone who looked at the diff.
[`design/PROCESS.md`](design/PROCESS.md) is how a design becomes code: the designer draws the round
in this repository and renders it with `scripts/render-design.sh`, the owner reviews the pictures on
its task, and the gate promotes it. Claude Design was retired on 2026-10-22 (DEB-2112). Read
`PROCESS.md` before touching anything under `design/`.

**Push `main` to GitHub as you go** (owner, DEB-1173). When you have committed a finished piece of
work, run `scripts/check-deanonymization.sh` and `scripts/check-phases.sh`; if both pass,
`git push origin main`. A push to `main` runs the full CI and publishes images tagged with the commit
SHA only — it never moves `latest`, cuts a release or deploys anything, so it is not a release call,
and it is how the constitution's "CI green" becomes something you can actually read. Never
`--force`, never `--tags`, never another branch. A run that has started always finishes (DEB-2460): a newer push
waits behind it as the one pending run, so there is no point pushing more often than once a finished task. Tags and GitHub Releases are
Tróndur's.

**`main` reaches GitHub from the shared checkout only, and a rejected push is merged, not left**
(Tróndur, DEB-2254, 2026-10-23). This replaces the old "stop and say so": that rule left ten
commits from several seats stranded for over two hours, and the workaround was a push from a side worktree
that recopied them under new ids, which broke every SHA the board cites.
- **Push `main` only from `~/IdeaProjects/jellystructure`.** A side worktree is for building and
  testing. A reviewer lands a branch in the shared checkout and pushes from there.
- **If the push is rejected:** `git fetch origin`, then `git merge --no-edit origin/main`. If the merge
  is clean, run both checks again and push. A merge commit is fine. A commit already on the shared
  `main` is never rebased or cherry-picked under a new id: other seats' comments cite those SHAs.
- **If the merge conflicts:** `git merge --abort`, leave `main` as it was, and give Tróndur a row that
  names both sides. Never resolve another seat's conflict yourself.
- A red CI on the merge SHA is filed and fixed like any other red CI on `main`.

**Work under review is never pushed by the person who wrote it** (owner, DEB-2264). If your task
carries a review stage naming someone else — every task on an `emmy5/deb-n` branch does — the push
rule above is not yours: commit on your branch, set the task `done`, and stop. Never merge your
branch into `main`, never merge `origin/main` into your branch, and never `git push`. The reviewer
lands it in the shared checkout and pushes from there. Passing the two checks above is not permission to push; it was read that way
once (DEB-2275) and put a commit that did not compile on `main`.

## Building locally

See [`README.md`](README.md#building-from-source) for the task list. Two local gotchas it does not
carry:

- `JAVA_HOME` is not set in this checkout's shell and there is no `java` on `PATH`. Prefix Gradle
  invocations with a JDK 21: `JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew <task> --console=plain`.
- **Never run `./gradlew --stop`.** Gradle daemons are shared across invocations on this host, and
  stopping them has taken a live backend down mid-session. If a build looks wedged, retry it.

Do not deploy, restart a service, or touch a device without being asked to. Those are the owner's
calls, every time.

## Consistency checks

[`scripts/`](scripts/) holds the guards — `check-phases.sh` checks that every spec is a legacy
`phase-…` file whose number is unique and not above the frozen maximum, or a `<slug>.md` that names
its task on a `Task: DEB-n` line (it cannot reach the board, so it says nothing about status), and
the rest check CSS scoping, mobile CSS, the hand-written language/country tables, de-anonymisation
and more. Run the ones that touch what you changed.

## Historical note

Until 2026-10-12 this file was overwritten on every "updated designs" sync from a separate design
project, and described that project rather than this repository. That sync is retired. The old
content is kept, frozen and clearly marked, as
[`design/design-project-notes-2026-06-to-2026-10.md`](design/design-project-notes-2026-06-to-2026-10.md)
— a useful record of which design directions were drawn, picked and rejected, and a reliable record
of nothing else.
