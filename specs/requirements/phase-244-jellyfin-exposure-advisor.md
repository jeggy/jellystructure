# Phase 244 — Catch the Jellyfin configuration that leaves this server open, and say how to close it

> Owner direction 2026-09-18: the security issues we found are there because of my own
> configuration. Get this into jellystructure's settings UI so jellystructure catches it and guides
> us to solve it properly.

## Status

`✓ Built` — written 2026-09-18 from the upgrade audit, **dev-reviewed 2026-09-19 against `main`
`dcb97f2c`** (see §Dev review at the foot), **built 2026-09-19** on top of phase **246**, which landed
the shared `AdvisorFinding` severity/action extension the review asked for. Extends phase
**212**'s advisor with a security section. Sibling of **242** (metadata ownership); the two share the
advisor surface and nothing else. Evidence:
`specs/research-reports/jellyfin-12-1-upgrade-audit-2026-09-18.md`.

### How the build answers the dev review

**Item 1 — the blocking one — is answered without needing the missing measurement, by not depending on
it.** The review was right that the probe alone cannot be trusted here: jellystructure reaches Jellyfin
*through* the very proxy the finding is about, Caddy appends to `X-Forwarded-For`, and the
discriminating case was never measured through that path. So FR-244-1 is built as **two signals**, and
is definitive only where it genuinely is:

- **`KnownProxies` empty is decisive on its own, and is not inference about proxies.** With it empty
  Jellyfin ignores `X-Forwarded-For` outright, so every caller is classified by the address the request
  arrived from — behind any reverse proxy, a private one. Measured live 2026-09-19: `GET /System/Endpoint`
  returned `IsInNetwork: true` **identically with and without** the RFC 5737 header. That is the header
  being ignored, *observed* rather than assumed, and it is the household's current state.
- **`KnownProxies` set hands the question to the probe.** `IsInNetwork: false` clears the finding.
  `IsInNetwork: true` is the one state jellystructure cannot resolve from where it stands — a wrong proxy
  address and the append-hazard look identical — so it renders a distinct, lower-severity
  *`known_proxies_unconfirmed`* finding that says exactly that, naming a restart and a wrong value as the
  two candidates. It never claims the hole is open or closed on evidence it does not have.

This also means **acceptance 3 changes shape**: a wrong address leaves a finding standing, as required,
but it is the unconfirmed one rather than the original.

**Item 2 — the cache.** Re-check is a dedicated route, `GET /api/jellyfin/exposure-recheck`, running
only the probe, never a `force` flag on the advisor pass. It drops the cached pass on the way out so the
page agrees with the answer the operator was just given. `/health/full` calls the *non*-invalidating
`exposureCheck` instead, because a polled endpoint must not clear an advisory cache as a side effect.

**Item 3 — severity and action.** Landed in phase 246 as `AdvisorFinding.severity` and
`AdvisorFinding.action`, once, for both phases. `critical` sorts first with no second ordering rule
needed, and `action = "recheck_exposure"` is what the frontend binds the button to.

**Item 4 — the gate.** FR-244-2 now reads Jellyfin's own `EnableRemoteAccess`, not jellystructure's
`public_url`. **Open questions 2 and 3 close with it**, as the review predicted.

**Item 6 — open question 1's wording.** Both the finding's recommendation and the Re-check button's
"still reported open" reply say a restart may be needed and say to do it when nobody is watching. The
answer to whether a restart is *required* is still unknown; the copy is written so that it does not
matter which way it falls.

**Item 7 — FR-244-5 is not softened.** It ships naming the problem, stating that no fix exists, citing
`#1501`/`#5415`/`#13986`, and explicitly forbidding proxy-level auth on those paths because Ravilo's own
playback depends on them answering anonymously.

**FR-244-7 held throughout.** The only routes this phase touches are `GET /System/Configuration/network`
and `GET /System/Endpoint`. No lifecycle route is contacted by any code path.

**Acceptance 2 is a configuration action and is outstanding by design** — `KnownProxies` is still empty
on the household server as of 2026-09-19, and setting it is the operator's to do.

## What is wrong

The 2026-09-18 audit found that **anyone on the internet can restart the household's Jellyfin, with
no credential at all.** That is not a Jellyfin bug. It is three correct behaviours meeting:

1. `SystemController.RestartApplication` carries
   `[Authorize(Policy = Policies.LocalAccessOrRequiresElevation)]`. A caller Jellyfin considers
   in-network may restart it without authenticating. Deliberate.
2. Jellyfin decides "in-network" from the request's source address.
3. Jellyfin sits behind Caddy at `172.28.0.17`, a private address, and `KnownProxies` is **empty**.
   With it empty Jellyfin ignores `X-Forwarded-For` and sees only the proxy's address. Every internet
   request is therefore in-network.

This reaches the impact of **CVE-2025-32012** (CVSS 7.5, patched in 10.10.7) with none of the IP
spoofing that CVE describes. It was verified live on the household server:

```
GET /System/Endpoint, X-Forwarded-For: 203.0.113.9  ->  {"IsLocal":false,"IsInNetwork":true}
```

A caller claiming a public address is still classified in-network. Measured on a clean 12.1.0
instance, setting `KnownProxies` fixes it completely:

| `KnownProxies` | Caller presented as | `POST /System/Restart`, no credential |
|---|---|---|
| empty | anything via the proxy | 204, server restarts |
| set to the proxy | `203.0.113.9` | 401, refused |
| set to the proxy | `192.168.1.50` | 204, restarts, by design |

**Nothing told the operator.** Phase 212 built an advisor for exactly this situation — a Jellyfin
setting that only Jellyfin can change, that jellystructure can see and the operator cannot easily —
and it checks chapter images, trickplay, LUFS and the I/O scheduler. All performance. A
misconfiguration that opens the server to an unauthenticated restart from the internet renders
nowhere, in any surface, and would not have been found without an unrelated upgrade audit.

## Requirements

**FR-244-1 — Detect it by asking Jellyfin, not by inferring.** The check issues one read-only
`GET /System/Endpoint` carrying `X-Forwarded-For` set to an address from **`203.0.113.0/24`
(RFC 5737 TEST-NET-3)**. If the response reports `IsInNetwork: true`, Jellyfin has ignored the
header and every remote caller is classified in-network.

The documentation range is mandatory. A real address such as `8.8.8.8` must never be used, because
the probe would be asserting something about a third party's network.

This is a capability probe, not a guess at configuration. It answers the question the operator
actually has — "would a stranger's request count as local here?" — rather than reading a setting and
reasoning about what it implies.

**FR-244-2 — The finding is rendered when it matters.** It fires when FR-244-1's probe reports
`IsInNetwork: true` **and** jellystructure's own `public_url` is set, which is the configuration
meaning this installation is reached from outside. A LAN-only installation gets nothing: the restart
policy is doing what it was designed to do and there is no exposure to report.

**FR-244-3 — It states the consequence, not the setting.** The summary says what an operator loses,
in their own terms: anyone who can reach this server from the internet can restart it, with no
password, as often as they like. Phase 212's shape is kept — current value, cost, navigation path,
Jellyfin's exact on-screen field label, recommendation, trade-off — with the severity raised: this
renders **first**, above every performance finding, and is visually distinct from them.

**FR-244-4 — It guides the fix, and the fix is verifiable in place.** The finding carries the
navigation path to Dashboard → Networking → **Known proxies**, and explains that the value is the
address Jellyfin sees requests arriving from, which is the reverse proxy, not the client.

jellystructure must not guess that address. It cannot see it, and a wrong value silently leaves the
hole open while looking fixed. Instead the finding carries a **Re-check** action that re-runs
FR-244-1's probe on demand. A correct value makes the finding disappear; a wrong one leaves it
standing. The operator confirms the fix rather than being told it worked.

**FR-244-5 — Jellyfin's unauthenticated media routes are reported honestly, or not at all.** When
`public_url` is set, a second, lower-severity finding states that Jellyfin serves
`/Videos/{id}/stream` and `/Items/{id}/Images/*` to callers with no credential, so anyone holding an
item id can download a file. Verified on 12.1.0 and 10.11.11 alike.

This finding must **not** offer a fix that does not exist. There is no Jellyfin setting for it; it is
long-known upstream (`#1501`, `#5415`, `#13986`) and appears to be a deliberate compatibility trade
for DLNA and browser clients. The finding says so plainly, links the upstream issues, and names the
only real levers: do not expose the server, or accept it knowingly.

In particular it must **not** suggest requiring authentication at the reverse proxy for those paths.
Ravilo's own playback depends on them answering anonymously — the token in those URLs is ignored by
Jellyfin, per phase **239** — so proxy-level auth would break playback on every client in the
household. Saying that out loud is the point of the finding.

**FR-244-6 — Silence where it is already right.** Phase 212's discipline. A correctly configured
`KnownProxies`, or a LAN-only installation, produces no output at all. An advisor that recites
security advice at a server that is already correct trains the operator to ignore it.

**FR-244-7 — The advisor never probes destructively.** Lifecycle routes are off-limits to any check
in this phase, per **FR-240-6**. The whole point of FR-244-1 is that it reads a classification
instead of testing the restart by performing one. The one-minute outage during the audit is why this
is written down.

**FR-244-8 — `/health/full` carries the same two checks.** The advisor is the human surface; the
health endpoint is the machine-readable one, and they read the same probe so they cannot disagree.
Same reasoning as 242's FR-242-6.

## Non-goals

- Changing Jellyfin's configuration from jellystructure. The advisor reports; the operator acts. This
  is phase 212's rule and it is not reopened.
- Auto-discovering the reverse proxy's address. See FR-244-4: a wrong value is worse than no value,
  because it looks like a fix.
- A general Jellyfin security audit. Two findings, both verified on this server, both with a stated
  consequence. Phase 212 exists because a surface reciting best practice would have been mostly noise
  on day one, and that lesson applies here.
- Reporting `/System/Restart` upstream. It behaves as designed; see the audit report.
- Anything about `AudioController`, `HlsSegmentController` or `LiveTvController`, which were not
  tested.

## Acceptance

1. Against the household server as it stands today, both findings render, the `KnownProxies` one
   first.
2. Setting `KnownProxies` to Caddy's address and pressing **Re-check** clears the first finding, and
   `POST /System/Restart` with a public `X-Forwarded-For` and no credential then answers 401 instead
   of restarting the server.
3. Setting `KnownProxies` to a **wrong** address leaves the finding standing.
4. With `public_url` unset, neither finding renders.
5. No check in this phase issues a request to `/System/Restart`, `/System/Shutdown` or any other
   lifecycle route, and the probe's `X-Forwarded-For` is inside `203.0.113.0/24`.
6. `/health/full` reports both checks, agreeing with the advisor.

## Open questions

1. Does Jellyfin need a restart for a `KnownProxies` change to take effect? On the clean 12.1.0
   instance the value was set through `POST /System/Configuration/network` and the container was
   restarted before testing, so the two were not separated. If a restart is required, FR-244-4's
   **Re-check** must say so, or an operator will set the value correctly, re-check, still see the
   finding, and conclude the guidance is wrong.
2. Should the finding also fire when `public_url` is unset but Jellyfin's own `EnableRemoteAccess` is
   true? That is a second, weaker signal that the server is reachable from outside, and it would
   catch an installation exposed by something other than jellystructure's own configuration.
3. Is there any legitimate deployment where `KnownProxies` is empty, requests arrive from a private
   address, and the operator genuinely wants unauthenticated LAN restarts to be reachable from the
   internet? If not, FR-244-2's `public_url` condition may be unnecessary caution.

## Dev review (2026-09-19, against `main` `dcb97f2c`)

The finding is real, the direction is right, and phase 212's advisor is the right home. Traced against
`advisor/JellyfinAdvisorService.kt`, `config/AppConfig.kt`, `ui/Settings.kt` and the household's live
`config.toml`. **One measurement is missing and it decides whether FR-244-1 works at all**; two smaller
things block FR-244-4 as written.

1. **The probe may not be able to tell the two states apart, because of the path it takes.** The
   household's `jellyfin_url` is **`https://jellyfin.example.net`** — jellystructure reaches Jellyfin
   *through Caddy*, not directly. So FR-244-1's probe arrives at Jellyfin with an `X-Forwarded-For` that
   Caddy has already touched. Caddy's `reverse_proxy` **appends** the client address to an existing
   `X-Forwarded-For` rather than replacing it, so what Jellyfin evaluates is plausibly
   `203.0.113.9, <jellystructure's own private address>` — and which entry Jellyfin selects from that
   list decides the answer. If it takes the rightmost untrusted hop (the ASP.NET Core default shape),
   the probe reports `IsInNetwork: true` **whether or not `KnownProxies` is set**, the finding never
   clears, and acceptance 2 and 3 both fail.
   Crucially, **the audit's own table does not settle this**, because its two rows were measured in
   different topologies: the `KnownProxies: empty → IsInNetwork: true` row came from the household server
   (where XFF is ignored entirely, so both readings agree), and the `KnownProxies: set → 401` row came
   from "a clean 12.1.0 instance", which was not behind this Caddy. The discriminating case was never
   measured through the path jellystructure will actually use. This is the same hazard 236's dev review
   recorded as item 8(a) — "`X-Forwarded-For`'s first hop is client-controlled wherever a proxy appends
   instead of replacing" — arriving here as a correctness problem rather than a cosmetic one.
   **Run one measurement before building:** set `KnownProxies` on the household Jellyfin, then issue the
   exact probe jellystructure would issue — through `jellyfin.example.net`, with the RFC 5737 header —
   and confirm it flips to `IsInNetwork: false`. If it does not, FR-244-1 needs a different mechanism:
   either a header form Caddy does not merge, or reading `KnownProxies` from
   `GET /System/Configuration/network` and comparing it against the remote address `/System/Endpoint`
   reports. The second is inference, which FR-244-1 rightly dislikes — but honest inference beats a
   capability probe that cannot observe the capability.
2. **The advisor's 5-minute cache defeats FR-244-4's Re-check.** `findings()` returns `cached` for
   `CACHE_TTL_SEC = 300` (`JellyfinAdvisorService.kt:27-34`). A Re-check that calls the same endpoint
   gets the stale answer, so the operator sets `KnownProxies` correctly, presses Re-check, watches the
   finding stay, and concludes the guidance is wrong — precisely the failure OQ1 is worried about,
   reached by a different route and without any Jellyfin restart being involved. Re-check needs a
   cache-bypassing path: a `force` parameter on the advisor route, or better, a dedicated endpoint that
   runs **only** this probe, since re-running the whole advisor pass to answer one question is both
   slower and noisier. Keep it on the BACKGROUND gate either way (`:44`) — operator-initiated is still
   advisory.
3. **FR-244-3 and FR-244-4 both need model and frontend work the spec does not mention, and 242 needs
   the same.** `AdvisorFinding` is eight strings with no severity and no action (`:415-424`), and
   `advisorFindingHtml` renders static markup into a per-library or server-wide card
   (`Settings.kt:1258-1279`). "Renders **first**, visually distinct" needs a severity field and ordering;
   "carries a **Re-check** action" needs an action affordance and a click handler. Phase 242's review
   raised the sibling gap (its FR-242-7 state also has nowhere to live in the DTO). **Extend
   `AdvisorFinding` once, for both phases** — a severity and an optional action — rather than each
   growing its own special case. Whichever lands first does it.
4. **FR-244-2 gates on the wrong server, and this household proves it.** `public_url` is
   **jellystructure's** public address (`AppConfig.kt:27`) — on this server `https://jelly.example.net`
   — while Jellyfin is exposed separately at `https://jellyfin.example.net`. So `public_url` being set is
   evidence that *jellystructure* is reachable from outside, and the finding is about whether *Jellyfin*
   is. They happen to correlate here; nothing makes them. **This is the answer to open question 2, and it
   upgrades it from a nice-to-have to the right condition:** gate on Jellyfin's own `EnableRemoteAccess`,
   which is a fact about the server the finding describes, with `public_url` kept only as a secondary
   signal. A LAN-only Jellyfin behind an exposed jellystructure would otherwise get a security finding it
   cannot act on, and an exposed Jellyfin behind a LAN-only jellystructure — the more dangerous case —
   would get silence.
5. **Open question 3 closes, but not in the direction it leans.** There is no legitimate deployment
   wanting internet-reachable unauthenticated restarts, so the condition is not there to protect a valid
   configuration — it is there because jellystructure cannot see Jellyfin's exposure directly. Item 4
   gives it something that can. Keep a condition; change what it reads.
6. **Open question 1 stands, and its wording matters more than its answer.** If a `KnownProxies` change
   needs a Jellyfin restart, the Re-check copy must say so — and it must say it in household terms, not
   as an instruction to restart immediately: this server has live viewers, and a restart mid-playback is
   exactly the one-minute outage FR-244-7 exists to avoid causing. "Jellyfin may need a restart before
   this takes effect — do it when nobody is watching" is the shape.
7. **FR-244-5 is the best-written requirement in the cluster and should not be softened in review.**
   Naming a problem, stating that no fix exists, linking upstream, and explicitly forbidding the
   plausible-but-wrong mitigation (proxy-level auth, which phase 239 confirms would break every client
   because the token in those URLs is ignored) is exactly right. Worth one addition: say *when* it was
   verified and against which versions inline in the finding, per 243's FR-243-5, so a future reader can
   tell whether it is still true.
