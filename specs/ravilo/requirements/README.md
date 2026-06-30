# Ravilo — Requirements (spec content)

One file per phase (`phase-RNN-*.md`), flat in this directory. These are **content only** — phase
**status is tracked in the repo-root [`STATUS.md`](../../../STATUS.md)**, the single source of truth.

- Ravilo architecture: [`../constitution.md`](../constitution.md) · [`../plan.md`](../plan.md)
- Admin specs: [`../../requirements/`](../../requirements/)

After an "updated designs" sync, run [`scripts/check-phases.sh`](../../../scripts/check-phases.sh).

## Numbering policy

Spec numbers are assigned **monotonically and never reused** — even when a spec file is deleted, its
number is retired forever. The next new Ravilo spec is **R149**.

The 87 oldest specs (**R01–R87**) were **deleted 2026-06-30** to declutter; only the newest 15
(R127, R130, R131, R133–R143, R148) remain as files. Removed specs live in **git history**
(`git log -- specs/ravilo/requirements/phase-RNN-*.md`). Status for every phase stays in
[`STATUS.md`](../../../STATUS.md).
