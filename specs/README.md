# specs/

Spec-driven development for **jellystructure**. This directory is the single home for the project's
rules, requirements, and architecture. Code follows the specs; when behaviour changes, the spec
changes with it.

| File | What it holds |
|------|---------------|
| [`constitution.md`](constitution.md) | Non-negotiable rules: tech mandates, architectural invariants, visual system. **Wins on conflict.** |
| [`requirements/`](requirements/) | The **"what"** — one flat file per phase, **content only**. See the [index](requirements/README.md). Ravilo: [`ravilo/requirements/`](ravilo/requirements/). |
| [`plan.md`](plan.md) | The **"how"** — source layout, data models, API routes, UI pages, WebSocket protocol, scanner flow. |
| [`research-reports/`](research-reports/) | Standalone **research & investigation reports** — dated deep dives behind a decision or future phase. Research, not spec; may go stale. See the [index](research-reports/README.md). |

**Phase status lives in the repo-root [`STATUS.md`](../STATUS.md)** — the single source of truth for
both products (admin numeric + Ravilo `R…`). A spec's file location or body no longer encodes status.

## Reading order
1. [`../STATUS.md`](../STATUS.md) — what's done, what's next.
2. The relevant phase spec in [`requirements/`](requirements/).
3. [`plan.md`](plan.md) — where the relevant code lives.
4. [`constitution.md`](constitution.md) — the rules that constrain any change.

Project overview and build commands live in the repo-root [`CLAUDE.md`](../CLAUDE.md).
