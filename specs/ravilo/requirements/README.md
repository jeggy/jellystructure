# Ravilo — Requirements (spec content)

One file per piece of work, flat in this directory. These are **content only** — a spec's **status
is tracked on its board task**, the single source of truth.

- **New work** (owner, DEB-799, 2026-10-15): `<slug>.md`, with `Task: DEB-n` on the line under the
  title. The task is the identity and carries the `ravilo-client` label; FRs are numbered inside the
  spec (`FR-1`, `FR-2`, …) and commits cite `(DEB-n, FR-k)`. No phase number.
- **Legacy phases**: `phase-RNN-*.md`, numbers frozen at R388 — the last number issued. Commits, FR
  ids and the constitution cite them; an amendment to one stays in its file.

- Ravilo architecture: [`../constitution.md`](../constitution.md) · [`../plan.md`](../plan.md)
- Admin specs: [`../../requirements/`](../../requirements/)

After an "updated designs" sync, run [`scripts/check-phases.sh`](../../../scripts/check-phases.sh):
it flags a spec whose filename does not parse, a legacy phase number used twice or above the frozen
maximum (R388), and a `<slug>.md` spec with no `Task: DEB-<n>` line in its first five lines.
