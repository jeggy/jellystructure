# specs/

Spec-driven development for **jellystructure**. This directory is the single home for the project's
rules, requirements, and architecture. Code follows the specs; when behaviour changes, the spec
changes with it.

| File | What it holds |
|------|---------------|
| [`constitution.md`](constitution.md) | Non-negotiable rules: tech mandates, architectural invariants, visual system. **Wins on conflict.** |
| [`requirements/`](requirements/) | The **"what"** — one flat file per piece of work, **content only**. See the [index](requirements/README.md). Ravilo: [`ravilo/requirements/`](ravilo/requirements/). |
| [`plan.md`](plan.md) | The **"how"** — source layout, data models, API routes, UI pages, WebSocket protocol, scanner flow. |
| [`research-reports/`](research-reports/) | Standalone **research & investigation reports** — dated deep dives behind a decision or future phase. Research, not spec; may go stale. See the [index](research-reports/README.md). |

**Status lives only on the board** — each piece of work's task is the single source of truth for
both products. A spec's file location or body does not encode status.

**The task is the identity; new work has no phase number** (owner, DEB-799, 2026-10-15). A new spec
is a `<slug>.md` in the tree it belongs to, with `Task: DEB-n` on the line under the title; its FRs
are numbered inside the spec (`FR-1`, `FR-2`, …) and commits cite `(DEB-n, FR-k)`. The task's
`ravilo-client` / `jellystructure-admin` label says which tree — both when it spans. The legacy
`phase-…` files keep their numbers, frozen at 321 (admin) and R388 (Ravilo); an amendment to one
stays in its file.

## Reading order
1. The board — what's done, what's next.
2. The relevant spec in [`requirements/`](requirements/) or [`ravilo/requirements/`](ravilo/requirements/).
3. [`plan.md`](plan.md) — where the relevant code lives.
4. [`constitution.md`](constitution.md) — the rules that constrain any change.

Project overview and build commands live in the repo-root [`README.md`](../README.md); the rules for
working in this repo — including that the task, not a number, is a piece of work's identity — are
in [`CLAUDE.md`](../CLAUDE.md).
