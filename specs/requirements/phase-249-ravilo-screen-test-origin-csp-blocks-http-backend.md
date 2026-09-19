# Phase 249 — The `ravilo-screen` test origin's CSP blocks a plain-`http` backend

## Status

`✓ Built` — written and built 2026-09-19, following straight on from 248's first real CI run (which
failed on exactly this), verified locally against the real `web-static-server` binary before and after
the fix. Not dev-reviewed.

## What is wrong

248's `ravilo-screen-cast.spec.ts` failed its first real CI run — not on anything about casting, but on
R269's setup screen never completing at all: stuck showing *"There's no Ravilo server at that address"*
against `http://app:9505`, an address that is exactly right. `247`'s own CORS work made this same
symptom familiar, so it looked like a CORS regression at first. It measured out to something else
entirely, confirmed by reproducing it locally with the real `web-static-server` binary (not the plain
Python static server used to verify 247/248 locally, which sends no security headers at all and so never
exercised this):

```
Connecting to 'http://127.0.0.1:19506/api/health' violates the following Content Security Policy
directive: "connect-src 'self' ws: wss: https:". The action has been blocked.
```

`web-static-server`'s CSP (`Main.kt:227`, `RAVILO_WEB_CSP`) allows `connect-src` from `'self'`, `ws:`,
`wss:` and `https:` — never plain `http:`. `docker-compose.test.yml`'s mock stack talks plain HTTP
throughout (matching every other spec in the suite), so the browser refused the request before CORS
ever entered the picture.

**This is confirmed to be a test-infrastructure artifact, not a confirmed production bug — the
distinction matters and is stated plainly rather than guessed past:**

- `web-static-server`'s CSP exists to protect **`ravilo-web`**, a PWA a household reaches over the
  internet, where production is essentially always HTTPS (phase 235's own security work). Real
  `ravilo-web` deployments are unaffected by anything in this phase.
- `ravilo-screen` is never served over HTTP in production at all — its only real distribution is the
  packaged `.wgt` (Phase R272's CI pipeline → a GitHub Release → a manual Seller Office upload). A
  Tizen widget loaded from local package storage carries **no HTTP response and no `<meta>` CSP tag**
  (`ravilo-screen/wgt/index.html` has neither) — so a real device very likely has **no CSP applied to
  it at all**. `web-static-server` only serves `ravilo-screen`'s bundle here because phase 247/248
  reused it to give the test bundle a real second HTTP origin; that reuse is what introduced a CSP the
  real device almost certainly never sees.
- Whether Tizen's own WebKit imposes some other restriction on a `file://`-scheme (or packaged-app
  scheme) widget calling a plain-`http` backend is a **separate, still-open question** — the same
  "needs real hardware" class as 247's own open question 1. This phase does not answer it, and does not
  claim to.

So the fix scope is: unblock the test, for the test's own reason, **without weakening `ravilo-web`'s
real production CSP** — not "loosen security because a Tizen TV might use `http`," which is an
unverified claim this phase deliberately does not make.

## Requirements

**FR-249-1 — An opt-in, additive env var, default off.** `web-static-server` gains
`CSP_ALLOW_HTTP_CONNECT` (any non-blank value = on). When unset — every real deployment, including
`ravilo-web`'s — the emitted CSP string is **byte-for-byte identical** to today's, preserving FR-235-6's
own acceptance test (the string is compared verbatim against `Server.kt`'s `/tv/**` policy). When set,
`connect-src` gains one extra scheme: `'self' ws: wss: https: http:`. Nothing else in the policy
changes, and no other route or config in `web-static-server` is affected.

**FR-249-2 — Only the test stack's `ravilo-screen` service sets it.** `docker-compose.test.yml`'s
`ravilo-screen` service gets `CSP_ALLOW_HTTP_CONNECT: "1"`. `ravilo-web`'s test-stack service is
untouched (it already talks to `app` correctly under `https:` in production and doesn't need this).

**FR-249-3 — Verified against the real binary, not the local stand-in.** 247/248's local verification
used a plain Python static server, which sends no CSP header at all and so could never have caught
this. Confirmed here by building `web-static-server` locally and serving the real bundle through it —
first reproducing the failure with the flag off, then confirming the fix with it on — the same
"revert and watch it fail, then confirm the fix" discipline 248 used for its own three bugs.

## Non-goals

- Changing `ravilo-web`'s CSP, or anything about `Server.kt`'s `/tv/**` policy. Both are real production
  security surfaces with real deployment history (phase 235's `finding M1`-adjacent hardening); this
  phase touches neither.
- Answering whether a real Tizen device enforces any CSP-like restriction of its own. Recorded as an
  open question, same as 247's OQ1 — only resolvable on real hardware/emulator.
- Making the CSP fully configurable/pluggable. One narrow, named, additive flag for one narrow,
  named reason — not a general escape hatch.

## Acceptance

1. `docker compose config` shows `CSP_ALLOW_HTTP_CONNECT` set only on the `ravilo-screen` service.
2. With the flag unset, `web-static-server`'s emitted CSP header is byte-for-byte the pre-existing
   string — verified by diffing it against the string recorded in this spec.
3. With the flag set, a page served by `web-static-server` can `fetch()` a plain-`http` URL without a
   CSP violation — verified locally against the real binary (not the Python stand-in).
4. `ravilo-screen-cast.spec.ts` (248) passes in real CI.
5. `ravilo-web.spec.ts` / `ravilo-web-headers.spec.ts` (235) still pass unmodified — the CSP string
   comparison they carry is untouched for that service.

## Open questions

1. Does a real Tizen `.wgt`, loaded from local package storage, have any CSP-equivalent restriction on
   calling a plain-`http` backend at all? Unknown; only the emulator/hardware test already named in
   247/R264's open questions can answer it. If it turns out real hardware DOES block this, the fix
   belongs in `Screen.kt`/the widget's own manifest, not here.
