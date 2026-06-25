# Requirements (admin) — spec content

One file per phase (`phase-NN-*.md`), flat in this directory. These are **content only** — a phase's
**status is tracked in the repo-root [`STATUS.md`](../../STATUS.md)**, the single source of truth for
both products. A spec's location or body no longer encodes status.

- Architecture: [`../constitution.md`](../constitution.md) · [`../plan.md`](../plan.md)
- Cross-cutting findings: [`_investigation-findings.md`](_investigation-findings.md)
- Ravilo specs: [`../ravilo/requirements/`](../ravilo/requirements/)

After an "updated designs" sync, run [`scripts/check-phases.sh`](../../scripts/check-phases.sh): it
flags any spec here with no row in `STATUS.md`, and any `STATUS.md` row whose spec went missing.
