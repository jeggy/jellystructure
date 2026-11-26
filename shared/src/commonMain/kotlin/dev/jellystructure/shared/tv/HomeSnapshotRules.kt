package dev.jellystructure.shared.tv

/**
 * R212 / DEB-5697 (FR-61) — the rules a saved Home feed is kept by, shared by Android TV's `HomeSnapshotCache` and the Samsung's
 * `ScreenHomeSnapshot`: a snapshot older than this is treated as absent (phase-R212 FR-RV-R212-4) — a starting point, not load-bearing.
 */
const val SNAPSHOT_STALE_AFTER_MS: Long = 7L * 24 * 60 * 60 * 1000

/** Defensive backstop (phase-R212 "Storage guardrails"): a realistic Home feed encodes to well under this, and a larger one is not saved. */
const val SNAPSHOT_MAX_BYTES: Long = 2L * 1024 * 1024

/**
 * Pure staleness check (FR-RV-R212-4). `nowEpochMs` is a parameter so tests can pin "now"; callers pass the real time. A negative age
 * (clock skew) is stale, not fresh.
 */
fun isSnapshotFresh(savedAtEpochMs: Long, nowEpochMs: Long): Boolean = (nowEpochMs - savedAtEpochMs) in 0..SNAPSHOT_STALE_AFTER_MS

/** The size guard: an encoded snapshot over [SNAPSHOT_MAX_BYTES] is not written. */
fun exceedsSnapshotSizeCap(json: String): Boolean = json.length.toLong() > SNAPSHOT_MAX_BYTES
