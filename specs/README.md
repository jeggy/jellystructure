# specs/

Spec-driven development for **jellystructure**. This directory is the single home for the project's
rules, requirements, architecture, and current state. Code follows the specs; when behaviour changes,
the spec changes with it.

| File | What it holds |
|------|---------------|
| [`constitution.md`](constitution.md) | Non-negotiable rules: tech mandates, architectural invariants, visual system. **Wins on conflict.** |
| [`requirements/`](requirements/) | The **"what"** — one file per phase. Active at top level, completed under [`archive/`](requirements/archive/). See the [index](requirements/README.md). |
| [`plan.md`](plan.md) | The **"how"** — source layout, data models, API routes, UI pages, WebSocket protocol, scanner flow. |
| [`tasks.md`](tasks.md) | Step-by-step TODO for the active phase. |
| [`STATUS.md`](STATUS.md) | Where work currently stands. Read first each session. |

## Reading order
1. [`STATUS.md`](STATUS.md) — what's done, what's next.
2. The active phase spec in [`requirements/`](requirements/).
3. [`plan.md`](plan.md) — where the relevant code lives.
4. [`constitution.md`](constitution.md) — the rules that constrain any change.

Project overview and build commands live in the repo-root [`CLAUDE.md`](../CLAUDE.md).
