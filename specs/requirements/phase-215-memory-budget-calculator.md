# Phase 215 — a memory budget calculator: "here is the RAM I'll give it, tell me what to change"

**Status:** Planned
**Authored:** 2026-09-15 (design-authored with the owner, not dev-reviewed)
**Depends on:** Phase 212 (the advisory surface this extends, and its silence-on-match rule)
**Related:** Phase 213 §2.1 (jellystructure cannot meter work inside Jellyfin — the same blind spot
applies to memory)

---

## 1. Why

Phase 212 answers *"what is misconfigured?"* by diffing live values against recommendations. It cannot
answer the question the owner actually asked next, because that question has an input:

> I would love to set some limits. Maybe a calculator, where I just put how much RAM I'm willing to
> give jellystructure/jellyfin and then the calculator says what we should change in jellyfin or on
> `docker-compose.yml`.

That is a different tool. 212 is a differ; this is a **calculator**: one number in, a set of concrete,
copy-pasteable changes out, across three places that must agree with each other — Jellyfin's own
settings, two `docker-compose.yml` files, and host sysctls.

It matters here because this host's memory is both enormous and **entirely ungoverned**, and the
measurements say nobody has ever decided how it should be divided.

### Measured, 2026-09-15

| Fact | Value | Why it matters |
|---|---|---|
| Host RAM | 125 GB (23 GB used, ~102 GB page cache) | |
| **Jellyfin container memory limit** | **none** (`HostConfig.Memory = 0`) | It can take the whole host |
| jellystructure limit | 16 GiB (matches its compose `mem_limit: 16g`) | The only bounded one |
| Jellyfin `/transcode` | bind-mount of the **host's** `/dev/shm`, **63 GB** | Not bounded by the container's `shm_size: 64 MB` — that governs the container's *own* `/dev/shm`, not this bind |
| `EnableSegmentDeletion` | `false`, `SegmentKeepSeconds: 720` | Transcode segments accumulate in that tmpfs for a whole session |
| Swap | 47 GB, **12 GB in use** | With 102 GB of cache, on `vm.swappiness = 60` |
| Playing file residency | 24.7 GB film, **0.7 %** cached | The largest resource is idle during the workload that hurts |

Read together: an unlimited-memory Jellyfin writes transcode segments it never deletes into a 63 GB
RAM disk, on a host whose kernel is already swapping 12 GB out. Nothing here is *wrong* today — it has
not fallen over — but nothing is *decided* either, and the failure mode is a host OOM during a long 4K
transcode rather than a graceful degradation.

## 2. Shape

**One input, three outputs, suggest-only** — same posture as Phase 212 (§2): jellystructure never
writes Jellyfin config, never edits a compose file, never runs a sysctl. It computes and it explains.

**Input.** A single figure: *how much of this host's memory may the media stack use?* Expressed in GB
with the host's total and current usage shown beside it, so the number is chosen against reality rather
than guessed.

**Outputs**, each copy-pasteable and each naming its exact destination:

1. **`docker-compose.yml` fragments** — one per service, with the file path (`~/jellystructure/docker-compose.yml`
   and `/home/jeggy/jellyfin/docker-compose.yml`, both discoverable from the container labels), showing
   the `mem_limit` / `tmpfs` lines to change and what they read now.
2. **Jellyfin settings** — exact navigation path and exact on-screen field label, per Phase 212's
   FR-212-3 rule (labels extracted from the server's own `jellyfin-web` string table, never API
   property names).
3. **Host sysctls** — with the `/etc/sysctl.d` line needed to persist them, because none of these
   survive a reboot.

## 3. Requirements

### FR-215-1 — the arithmetic is shown, not just the answer

The operator is being asked to hand a number to a tool that will tell them to cap their media server's
memory. A wrong answer here does not produce a slow library page — it produces an OOM kill during
someone's film. So the calculator shows its working: what it reserved, for whom, and what it left
behind.

A result the operator cannot check is a result they should not apply.

### FR-215-2 — page cache is not free memory, and not spare memory either

The single largest trap in this calculation. `free -h` reports ~102 GB of `buff/cache` and ~102 GB
`available`. Both are true and neither means "102 GB is going spare":

- It is **reclaimable**, so a budget may count it as headroom.
- It is also **doing the job** — it is the only reason repeat reads of media are not all hitting a
  spinning disk.

The calculator must never present cache as free capacity to be allocated away, and must never treat a
budget that evicts most of it as costless. Where a proposed budget would materially shrink the cache,
it says so as a named trade rather than silently absorbing it.

### FR-215-3 — tmpfs is the dangerous one, and gets a cap

Highest-value output on this host. `/transcode` resolves to a **63 GB host tmpfs** (the kernel's
default of half of RAM), it is a *bind mount* so `shm_size` does not bound it, and Jellyfin is
configured never to delete segments during a session.

Given a budget, the calculator emits an explicit tmpfs size rather than leaving the default, and pairs
it — always, not conditionally — with Jellyfin's **"Delete segments"** (`AllowSegmentDeletion`) and
**"Time to keep segments"** (`LabelSegmentKeepSeconds`) under **Dashboard → Playback → Transcoding**.
A size cap without segment deletion just converts an OOM into a failed transcode; the two are one
recommendation.

This is the same coupling Phase 212's FR-212-5(b) identified from the other direction: a finding whose
trigger is another setting's value. Here it becomes a finding whose *output* is a pair.

### FR-215-4 — an unlimited container is always a finding

Jellyfin runs with no `mem_limit`. Whatever budget is chosen, the calculator emits a `mem_limit` for
it, because an unbounded container makes every other number in the budget advisory.

It also states the trade honestly: a memory limit turns "the host OOMs unpredictably" into "Jellyfin
gets OOM-killed predictably", and for a media server mid-playback that is better but not *good*. The
row says which failure the operator is choosing, not just that they should choose.

### FR-215-5 — swap and swappiness are part of the budget

12 GB is swapped out on a host with 102 GB of cache, at `vm.swappiness = 60`. That is the kernel
choosing to page out live processes to grow cache — the wrong trade when one of those processes is
serving video.

The calculator emits a `vm.swappiness` recommendation as part of the budget, with the `/etc/sysctl.d`
line to persist it, and states what it costs.

### FR-215-6 — it computes a budget, it does not spend it

Explicitly out of scope: implementing the prefetch feature from Phase 212's open question 6. If that is
ever built, its RAM allowance becomes another line in this calculator's output.

This phase must be useful **without** it — every output in §2 is worth having on a host where the
prefetch idea is never built. Stated so the calculator is not quietly blocked on a much larger feature.

### FR-215-8 — the budget must survive a neighbour that arrives later

(Raised by the owner, 2026-09-15: *"if at some point I'd have some other random service taking up
60 GB, would that break our setup, or only make the prefetch idea not fully available and then skip
it?"*)

The calculator must not model allocation only at time zero. This host runs ~30 containers; a new one
taking 60 GB is a realistic future, and the budget's job is to make that a slowdown rather than an
outage. **The answer differs by memory kind, and the design depends on the distinction:**

| Kind | Reclaimable? | Behaviour when a neighbour takes 60 GB |
|---|---|---|
| Page cache (incl. any prefetch) | **Yes**, by the kernel, automatically | Evicted. Prefetch silently stops helping; playback falls back to disk. **Degrades correctly with no work from us.** |
| **tmpfs** (`/dev/shm` → `/transcode`, `/tmp`) | **No** — pages can only go to swap | Cannot be dropped. Kernel swaps, then OOM-kills. **This is the break path.** |
| Container RSS | No | Bounded only where a `mem_limit` exists |

So the owner's hoped-for behaviour — *"skip it and my clients still work"* — is **already true for the
prefetch idea** and is **not** true for tmpfs. Two requirements follow.

**(a) Anything cache-shaped must be best-effort, and must back off.** A prefetch that re-reads files
the kernel is actively evicting turns graceful degradation into thrashing — prefetch 20 GB, get
evicted, prefetch again — which is worse than never prefetching. Gracefulness here is a property of
page cache, but *non-thrashing* is not: it has to be built. Any future prefetch reads memory pressure
and stops, and the calculator's prefetch allowance is a ceiling, never a reservation.

**(b) tmpfs must be capped, because nothing else will cap it.** Measured on this host today:

```
/dev/shm   63 GB cap, 0 GB used      -> Jellyfin's /transcode bind mount
/tmp       63 GB cap, 6.2 GB used    -> a second unbounded RAM disk, already holding 6.2 GB
```

Both default to half of RAM. With `EnableSegmentDeletion: false` and a 720 s keep, a long 4K transcode
writes into `/dev/shm` and nothing removes it for the session's duration. A 60 GB neighbour *alone* is
survivable; a 60 GB neighbour **plus** a transcode filling tmpfs is not, and the kernel's only options
are swap and the OOM killer. `/tmp` is called out because it is the same hazard from a source this
project does not own, and a budget that caps `/dev/shm` while ignoring a 63 GB `/tmp` has bounded half
the problem.

**(c) Say who should die.** All of `jellyfin`, `immich_server`, `immich_postgres` and `umami-db` run
with **no memory limit**, and measured OOM scores are effectively tied — `jellyfin` 671 versus
`jellystructure` 678, both at `oom_score_adj = 0`. Under host pressure the kernel picks by RSS
heuristic, so the process serving someone's film is as likely a victim as a batch job. The calculator's
`mem_limit` output is what converts an arbitrary host-wide OOM into a bounded, predictable failure of
one named service, and it should say that is what the operator is buying.

**Reassurance the output should carry, because it is true and it is not obvious:** real usage today is
small — Jellyfin 3.5 GB, jellystructure 3.0 GB, nothing else above 1.3 GB, against 125 GB total and
34 GB of free swap. A 60 GB neighbour would fit today without touching anything. The risk being
designed against is not the neighbour; it is the neighbour arriving while tmpfs holds an unbounded
transcode.

### FR-215-7 — refuse to over-commit

If the entered budget exceeds what the host can honour once existing non-media usage and a safety
reserve are accounted for, the calculator says so and emits nothing. It does not emit a best-effort set
of numbers with a warning attached.

A configuration that OOMs is worse than no recommendation, and a warning above a copy-pasteable block
will lose to the copy-pasteable block every time.

## 4. Open questions

1. **What is the safety reserve?** FR-215-7 needs a floor for the OS, the other ~15 containers on this
   host (Immich, qBittorrent, Postgres, Caddy…) and burst headroom. Measuring current non-media usage
   is easy; deciding how much to hold back is a judgment the spec does not make.
2. **One budget or two?** The owner's phrasing was "jellystructure/jellyfin" as a unit. They have very
   different profiles — jellystructure is a bounded Kotlin/Native server, Jellyfin is an unbounded
   .NET server that also spawns ffmpeg. A single figure is simpler to enter; two are more accurate.
   Unresolved, and it changes the whole input design.
3. **Does it re-check afterwards?** Phase 212's findings are live-diffed, so an applied change stops
   being reported. A calculator has no such feedback unless it also diffs its own recommendations
   against live values — at which point it is partly 212 again. Whether that overlap is duplication or
   the right integration is undecided.
4. **Does ffmpeg memory count against the budget?** Jellyfin's transcodes are child processes inside
   its cgroup, so a `mem_limit` does bound them — meaning a limit tuned for the server alone will
   kill transcodes. The calculator needs a per-concurrent-transcode allowance, and that number is not
   yet measured here.
5. **Where does it live?** Phase 212 chose per-library placement for findings that are per-library.
   This is inherently host-wide and has an input, so it is probably its own page rather than a card on
   Settings → Libraries. Not decided.

## 5. Verification

- Entering the host's current effective allocation must produce **no changes** for anything already
  correct — the same silence rule as Phase 212, applied to a calculator.
- Entering a deliberately over-large budget must produce nothing but the refusal (FR-215-7).
- Every emitted fragment must be valid where it lands: the compose fragments must parse, and the
  sysctl lines must be accepted by `sysctl -p`.
- On this host the output must include a Jellyfin `mem_limit` (FR-215-4), a bounded `/transcode` size
  paired with segment deletion (FR-215-3), and a `vm.swappiness` change (FR-215-5).
