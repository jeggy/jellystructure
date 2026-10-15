# Requirements (admin) — spec content

One file per piece of work, flat in this directory. These are **content only** — a spec's **status
is tracked on its board task**, the single source of truth for both products. A spec's location or
body does not encode status.

- **New work** (owner, DEB-799, 2026-10-15): `<slug>.md`, with `Task: DEB-n` on the line under the
  title. The task is the identity and carries the `jellystructure-admin` label; FRs are numbered
  inside the spec (`FR-1`, `FR-2`, …) and commits cite `(DEB-n, FR-k)`. No phase number.
- **Legacy phases**: `phase-NN-*.md`, numbers frozen at 321. Commits, FR ids and the constitution
  cite them; an amendment to one stays in its file.

- Architecture: [`../constitution.md`](../constitution.md) · [`../plan.md`](../plan.md)
- Cross-cutting findings: [`_investigation-findings.md`](_investigation-findings.md)
- Ravilo specs: [`../ravilo/requirements/`](../ravilo/requirements/)

After an "updated designs" sync, run [`scripts/check-phases.sh`](../../scripts/check-phases.sh): it
flags a spec here whose filename does not parse, a legacy phase number used twice or above the
frozen maximum (321), and a `<slug>.md` spec with no `Task: DEB-<n>` line in its first five lines.

Also run [`scripts/check-fd-hygiene.sh`](../../scripts/check-fd-hygiene.sh) (Phase 134 — unclosed
`SystemFileSystem.source()`/`.sink()` file-descriptor leaks) and
[`scripts/check-mobile-css.sh`](../../scripts/check-mobile-css.sh) (Phase 138 — design-sync-fragile CSS
rules in `design/app/app.css` that a wholesale sync export can silently strip).
