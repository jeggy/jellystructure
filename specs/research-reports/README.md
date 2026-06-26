# specs/research-reports/

Standalone **research & investigation reports** — deep dives that inform a decision or a future
phase but are not themselves specs. Each report is a point-in-time analysis (audit, feasibility
study, decoupling plan, perf investigation, etc.), often produced before the work is scoped into
`requirements/`.

**How this differs from the rest of `specs/`:**
- `constitution.md` / `plan.md` / `requirements/` describe what the system **is and must be** —
  they are kept current with the code.
- Reports here are **research, dated at the top**, and may go stale. They capture *why* a
  direction was chosen and the findings behind it. When a report's plan is adopted, the durable
  parts graduate into `requirements/` (a phase spec) and/or `constitution.md`; the report stays as
  the record of the analysis.

**Convention:** one file per report, kebab-case, with a `**Date:**` line near the top. Add a row
to the index below.

| Report | Date | Summary |
|--------|------|---------|
| [`ravilo-jellyfin-decoupling-investigation.md`](ravilo-jellyfin-decoupling-investigation.md) | 2026-06-26 | How much Ravilo depends on Jellyfin (direct + via jellystructure) and a plan to serve catalog, artwork, and per-user-state reads from jellystructure so the app loads faster — streaming stays on Jellyfin. |
| [`backend-performance-investigation.md`](backend-performance-investigation.md) | 2026-06-26 | Why the Library page, text search, and the Ravilo sofa feel slow: every read decodes the whole 18.7 MB / 307-item JSON-blob library (no SQL pushdown, no decode cache, no gzip). Prioritised workstreams (decode cache, projection, caching, transport, engine, filter caching) → specced as phases 88–90 + R86. Includes the no-flicker rule for Ravilo. |
