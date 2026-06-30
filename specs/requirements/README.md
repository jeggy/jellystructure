# Requirements (admin) — spec content

One file per phase (`phase-NN-*.md`), flat in this directory. These are **content only** — a phase's
**status is tracked in the repo-root [`STATUS.md`](../../STATUS.md)**, the single source of truth for
both products. A spec's location or body no longer encodes status.

- Architecture: [`../constitution.md`](../constitution.md) · [`../plan.md`](../plan.md)
- Cross-cutting findings: [`_investigation-findings.md`](_investigation-findings.md)
- Ravilo specs: [`../ravilo/requirements/`](../ravilo/requirements/)

After an "updated designs" sync, run [`scripts/check-phases.sh`](../../scripts/check-phases.sh): it
flags any spec here with no row in `STATUS.md`, and any `STATUS.md` row whose spec went missing.

## Numbering policy

Spec numbers are assigned **monotonically and never reused** — even when a spec file is deleted, its
number is retired forever. The next new admin spec is **105**.

The 71 oldest specs (**7–89**) were **deleted 2026-06-30** to declutter; only the newest 15
(90–104) remain as files. Removed specs live in **git history**
(`git log -- specs/requirements/phase-NN-*.md`). Status for every phase stays in
[`STATUS.md`](../../STATUS.md).
