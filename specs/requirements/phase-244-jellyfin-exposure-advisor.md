# Phase 244 — Catch the Jellyfin configuration that leaves this server open, and say how to close it

> Owner direction 2026-09-18: the security issues we found are there because of my own
> configuration. Get this into jellystructure's settings UI so jellystructure catches it and guides
> us to solve it properly.

## Status

`Planned` — written 2026-09-18 from the upgrade audit, not dev-reviewed, not built. Extends phase
**212**'s advisor with a security section. Sibling of **242** (metadata ownership); the two share the
advisor surface and nothing else. Evidence:
`specs/research-reports/jellyfin-12-1-upgrade-audit-2026-09-18.md`.

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
