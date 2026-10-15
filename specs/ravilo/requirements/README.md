# Ravilo — Requirements (spec content)

One file per phase (`phase-RNN-*.md`), flat in this directory. These are **content only** — phase
**status is tracked on the phase's board task**, the single source of truth.

- Ravilo architecture: [`../constitution.md`](../constitution.md) · [`../plan.md`](../plan.md)
- Admin specs: [`../../requirements/`](../../requirements/)

After an "updated designs" sync, run [`scripts/check-phases.sh`](../../../scripts/check-phases.sh):
it flags a spec whose filename does not parse, a legacy phase number used twice or above the frozen
maximum (R388), and a `<slug>.md` spec with no `Task: DEB-<n>` line in its first five lines.
