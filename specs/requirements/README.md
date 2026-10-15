# Requirements (admin) — spec content

One file per phase (`phase-NN-*.md`), flat in this directory. These are **content only** — a phase's
**status is tracked on the phase's board task**, the single source of truth for both products. A
spec's location or body does not encode status.

- Architecture: [`../constitution.md`](../constitution.md) · [`../plan.md`](../plan.md)
- Cross-cutting findings: [`_investigation-findings.md`](_investigation-findings.md)
- Ravilo specs: [`../ravilo/requirements/`](../ravilo/requirements/)

After an "updated designs" sync, run [`scripts/check-phases.sh`](../../scripts/check-phases.sh): it
flags a spec here whose filename does not parse, and any phase number used twice.

Also run [`scripts/check-fd-hygiene.sh`](../../scripts/check-fd-hygiene.sh) (Phase 134 — unclosed
`SystemFileSystem.source()`/`.sink()` file-descriptor leaks) and
[`scripts/check-mobile-css.sh`](../../scripts/check-mobile-css.sh) (Phase 138 — design-sync-fragile CSS
rules in `design/app/app.css` that a wholesale sync export can silently strip).
