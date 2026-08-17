# Internet exposure + Docker packaging — investigation

**Date:** 2026-08-17
**Scope:** what it takes to (a) publish jellystructure as Docker images on the private GHCR repo, (b) run them
from `~/jellyfin/docker-compose.yml` alongside the existing Jellyfin, and (c) be genuinely safe on the public
internet.
**Status:** investigation only — no code changed, nothing published.

Companions: [`backend-security-review-2026-08-02.md`](backend-security-review-2026-08-02.md) (the 21 in-app
findings, all fixed) and [`backend-deployment-guide-2026-08-02.md`](backend-deployment-guide-2026-08-02.md)
(the reverse-proxy mitigation for D1). **This report does not repeat those; it covers what changed since, what
they assumed that turns out to be wrong for this environment, and the packaging work neither covers.**

---

## Verdict

The security groundwork is genuinely done and I verified it live rather than trusting the report — C1
(percent-encoding auth bypass), C2 (arbitrary file read, both routes) and H1 (`/ws`) are all closed on the
running instance. The remaining work is **packaging and deployment plumbing, plus three CSP regressions that
the M8 fix introduced and nobody ever verified in a browser** — two of which are silently degrading the admin
UI *right now*.

Nothing here is architecturally hard. The honest blockers are a missing runtime dependency, a missing
`.dockerignore`, the CSP header, and the fact that **the one unfixable-in-app finding (D1) needs a Caddy plugin
this host's Caddy build doesn't have.**

---

## Part 1 — What already exists (don't rebuild these)

| Thing | State |
|---|---|
| `Dockerfile` | Multi-stage, working, CI builds it every run. Backend + admin frontend already in one image — **this is image #1, essentially done** |
| Admin frontend serving | `FRONTEND_DIR`, served at `/` |
| Ravilo web serving | `RAVILO_WEB_DIR` env var already exists, served at `/tv/**` (`Server.kt:600`) |
| Security fixes | All 21 in-app findings fixed; C1/C2/H1 re-verified live this session |
| `COOKIE_SECURE`, `CORS_ALLOWED_ORIGINS` | Implemented, both default to LAN-safe values |
| Login rate limiting | `LoginRateLimiter`, 5/60s, on both `/api/auth/login` and `/api/tv/login` |
| Security headers | HSTS, nosniff, frame-deny, referrer-policy, CSP — all sent (CSP has bugs, see F3) |
| SSRF guard | `util/UrlSafety.kt` |
| Secret file modes | `config.toml` and `jellystructure.db` are now `0600` (verified on disk) |
| Caddy + TLS | Already running: caddy-docker-proxy v2.11.4, label-driven, auto-HTTPS, DigitalOcean DNS challenge available |
| `trusted_proxies` | Already set on Caddy (`static 172.18.0.0/16 10.0.0.0/8`) — X-Forwarded-For will be trustworthy |

**The ravilo-web production bundle is self-contained.** I built it to check
(`:ravilo-web:wasmJsBrowserDistribution`): 17 MB, only relative refs in `index.html`, and skiko.wasm *is*
included (content-hashed to `bccfa839aa4b38489c76.wasm`, byte-identical to the original — the runtime-fetch
comment in `webpack.config.d/skiko.js` had me worried it might be missing). Fonts are bundled locally. So a
plain static-file image works with no special handling.

---

## Part 2 — Findings

### F1 🔴 `fpcalc` is missing from the runtime image — silently breaks segment detection

`Dockerfile:61` installs `ffmpeg mkvtoolnix wget libsqlite3-0`. The code also shells out to **`fpcalc`**
(Chromaprint), which `FfmpegRunner.kt:409` documents as *"must be present on PATH"*. Verified: it is **not** in
`debian:bookworm-slim` and is not installed.

That is the cross-episode audio fingerprinting behind Phase 150/159 Skip-Intro — a shipped, working feature.
`computeFingerprint` returns `null` on any failure, so **it degrades silently**: no crash, no error, intros just
stop being detected on new content. This is exactly the kind of thing that would be blamed on the detection
algorithm months later.

Fix: add `libchromaprint-tools` to the apt line. (`nice`/`ionice` I checked too — both already in the base image.)

### F2 🔴 No `.dockerignore` — 3.4 GB build context containing every secret

There is no `.dockerignore`. The build context is **3.4 GB**, of which `config/` is 1.3 GB and contains
`config.toml` (Jellyfin admin token, TMDB key, qBittorrent password, all *arr keys, webhook secret) and
`jellystructure.db` (84 MB — **live `js_session` tokens in plaintext**, replayable as cookies, plus every
Ravilo device's Jellyfin user token).

Today this is *latent*, not an active leak: the Dockerfile uses explicit `COPY` lines and never copies
`config/`, and CI builds from a clean checkout where `config/` is gitignored. But:

- Every local `docker build` ships 3.4 GB to the daemon before doing anything.
- One future `COPY . .` — or any `COPY config ...` for a sample file — bakes the secrets into a **published**
  image layer.
- Layers are forever. On a private GHCR repo that's less catastrophic, but it's still the whole *arr stack.

Fix: add `.dockerignore` (`config/`, `build/`, `.git/`, `*/build/`, `node_modules/`, `.gradle/`, `.kotlin/`,
`ravilo-android/`, `ravilo-phone/`, `towo-runner/`, `tests/`). Should cut the context to well under 100 MB.

### F3 🔴 Three CSP regressions — two are live and breaking the admin UI now

The M8 fix added a CSP the deployment guide explicitly flagged as *"NOT been verified against a real browser
session"*. It hasn't been, and it's wrong in three ways. Current header (verified live on `/` and `/tv/`):

```
default-src 'self'; script-src 'self' 'wasm-unsafe-eval' 'unsafe-eval';
style-src 'self' 'unsafe-inline'; font-src 'self' data:; img-src 'self' data: blob: https:;
connect-src 'self' ws: wss: https:; media-src 'self' blob: https:;
object-src 'none'; frame-ancestors 'none'; base-uri 'self'
```

**F3a + F3b — admin typography is blocked (live right now).** `design/app/wf.css:10` — served verbatim at
`/wf.css`, confirmed 200/44 KB on the live server — starts with:

```css
@import url('https://fonts.googleapis.com/css2?family=Sora...Space+Grotesk...JetBrains+Mono...');
```

`style-src 'self'` blocks the stylesheet; `font-src 'self' data:` blocks the `fonts.gstatic.com` font files.
So Space Grotesk / Sora / JetBrains Mono — the entire documented type system — are silently falling back to
system fonts in any browser that enforces CSP. This is **already happening**, not a future risk.

Two fixes, and I'd pick the second: allow `https://fonts.googleapis.com` / `https://fonts.gstatic.com`, **or**
self-host the three fonts. Ravilo already proves self-hosting works here — its bundle ships `space_grotesk.ttf`
and `sora.ttf` in `composeResources/`. Self-hosting also removes a third-party dependency from a service
that's about to face the internet, and keeps `default-src 'self'` honest.

**F3c — Ravilo trailers will be blocked.** There is **no `frame-src`** directive, so it falls back to
`default-src 'self'`. `trailerEmbedUrl()` builds `https://www.youtube-nocookie.com/embed/...` and
`https://player.vimeo.com/video/...` iframes (R163). Under this CSP those iframes are refused. This bites the
moment Ravilo web is served with this header — including today at `/tv/**` (CSP confirmed present on that path).

Fix: `frame-src https://www.youtube-nocookie.com https://player.vimeo.com`.

### F4 🟠 D1's mitigation needs a Caddy plugin that isn't installed

This is the one finding the security review said **cannot be fixed in-process**: Ktor Native's CIO engine uses
`select()`, so FD ≥ 1024 hard-aborts the process. The mitigation is connection/rate limiting at the proxy.

The deployment guide is written for **nginx** (`limit_req_zone` / `limit_conn_zone`). This environment doesn't
run nginx — it runs **caddy-docker-proxy**, and I checked the actual build
(`~/caddy/Dockerfile`, `caddy list-modules`):

```
--with caddy-docker-proxy/v2, caddy-umami, replace-response, caddy-dns/digitalocean
```

- **Rate limiting: not available.** No `http.handlers.rate_limit`. Caddy has no native rate limiter; it needs
  `--with github.com/mholt/caddy-ratelimit` and a Caddy image rebuild.
- **Per-IP connection caps: not available natively** in Caddy at all.
- **Body size cap: available** — `http.handlers.request_body` *is* present, so `request_body max_size 64MB`
  works today (covers the guide's M6 belt-and-suspenders item).

So the guide's §2 is not portable as written, and the D1 mitigation specifically is **blocked on a change to
the Caddy stack** (a different repo/compose project). That's worth knowing before anything is exposed, because
D1 is an unauthenticated remote process-abort.

### F5 🟠 Ravilo web on its own origin doesn't know where the backend is

`RaviloRootActuals.kt:68`:

```kotlin
actual fun raviloBaseUrl(): String = jsGetBaseUrl()?.takeIf { it.isNotBlank() } ?: jsOrigin()
```

It defaults to **its own page origin**. That's correct when the backend serves it (`/tv/**`), and wrong the
moment it's a separate image on `ravilo.example.com` — it would call `ravilo.example.com/api/...`, which is the
static server. The user would have to hit "Change server" manually on every device, and then it's cross-origin
(needs `CORS_ALLOWED_ORIGINS`).

**Recommended fix needs no code change:** have Caddy serve both from one hostname —
`/` → the ravilo-web static container, `/api/*` + `/ws` → the backend. Then `window.location.origin` is
correct, there's no CORS at all, and the Bearer device token (Ravilo uses `localStorage` + `Authorization`,
*not* cookies) works unchanged.

### F6 🟠 Library paths are absolute and must be mounted identically

`config.toml` carries a per-library `jellyfin_path` → `local_path` mapping:

| `jellyfin_path` (Jellyfin's container view) | `local_path` (jellystructure's view) |
|---|---|
| `/media/movies/` | `/mnt/media/jellyfin/movies/` |
| `/media/series/` | `/mnt/series/jellyfin/` |

jellystructure writes NFOs and remuxes files at `local_path`. The repo's existing `docker-compose.yml` mounts
`${MEDIA_PATH}:/media`, which does **not** match — containerising with that mapping breaks every path in the
DB and config.

Fix: mount **identity paths** (`/mnt/media/jellyfin/movies:/mnt/media/jellyfin/movies`,
`/mnt/series/jellyfin:/mnt/series/jellyfin`) so the existing config and database stay valid with zero
migration. Container must run as `1000:1000` to keep write access (media is `jeggy:jeggy`); the Dockerfile
already creates uid 1000.

### F7 🟠 No publish pipeline, and the host can't pull from GHCR yet

- No release/publish workflow exists (`.github/workflows/` has only `ci.yml` and `towo-runner-ci.yml`); no
  `ghcr.io` reference anywhere in the repo.
- `~/.docker/config.json` has auth for `registry.gitlab.com` only — **not** `ghcr.io`. Private-repo images
  require `docker login ghcr.io` with a PAT (`read:packages`) before compose can pull.
- Package visibility on GHCR is **independent of repo visibility** and defaults to private for a private repo —
  which is what's wanted, it just has to be linked to the repo for the PAT to work cleanly.

### F8 🟡 Production is currently a *debug* binary on 0.0.0.0

The live process is:

```
/home/jeggy/IdeaProjects/jellystructure/build/bin/linuxX64/debugExecutable/jellystructure.kexe
```

bound to `0.0.0.0:9505`. So today's production is a **debug** build (not `linkReleaseExecutableLinuxX64`, which
is what the Dockerfile builds), running from the build tree, on all interfaces. Moving to the image is
therefore also a debug→release switch — worth a perf sanity check after, and worth confirming the DB/config
volume points at the *existing* `config/` so nothing is lost.

### F9 🟡 Smaller things

- **Image size 886 MB.** App is only 28 MB + 4.2 MB frontend; the rest is ffmpeg's dependency tree. Mostly
  unavoidable, but `RUN chmod +x /app/jellystructure && chown -R` (`Dockerfile:69`) duplicates the whole 33 MB
  of copied content into a second layer — `COPY --chmod=755 --chown=...` avoids that for free.
- **No `HEALTHCHECK`** in the Dockerfile (the test compose defines one ad hoc). `/api/health` is deliberately
  unauthenticated for exactly this.
- **`ravilo.js.map` (1.7 MB) ships** in the production bundle — a source map exposing internal structure.
- **Ravilo device tokens never expire** (revocation is manual, admin-side). Fine on a LAN; on the internet a
  token lifted from `localStorage` is valid indefinitely. Worth a deliberate decision, not necessarily a change.
- **`.gitignore:36` is malformed** — `!config/config.example.toml.claude/` looks like two lines that lost their
  newline, so the negation for `config.example.toml` doesn't do what it reads like. Harmless today (the file is
  already tracked), but it will bite whoever edits it next.

---

## Part 3 — Proposed shape

### Two images, as asked

**`ghcr.io/jeggy/jellystructure`** — the existing Dockerfile, plus F1 (`libchromaprint-tools`), F2
(`.dockerignore`), and the F9 layer/healthcheck tidy-ups. Backend + admin frontend, one service.

**`ghcr.io/jeggy/ravilo-web`** — new, and genuinely tiny. The build stage already exists in the main
Dockerfile's builder (it copies `ravilo-ui`/`ravilo-web` for exactly this reason); it just needs
`:ravilo-web:wasmJsBrowserDistribution` and a static runtime:

```dockerfile
FROM caddy:alpine          # or nginx:alpine
COPY --from=builder /app/ravilo-web/build/dist/wasmJs/productionExecutable/ /srv/
```

~17 MB of assets. Serve with `Cache-Control: immutable` on the content-hashed `.wasm`/`.js` and `no-cache` on
`index.html`. Worth setting `application/wasm` explicitly and enabling precompressed gzip/brotli — the two wasm
files are 14.6 MB combined and compress well.

> Note: this is a genuine either/or with the existing `RAVILO_WEB_DIR` + `/tv/**` path, which already does the
> same job inside one image. The separate image is the right call if Ravilo should live on its own hostname and
> scale/deploy independently; if not, setting `RAVILO_WEB_DIR` is zero new infrastructure. Flagging it because
> the second image is real ongoing cost (another build, another publish, another thing to version-match).

### Compose sketch for `~/jellyfin/docker-compose.yml`

Adds to the existing file, reusing the external `caddy` network. **Not applied — illustration only.**

```yaml
  jellystructure:
    image: ghcr.io/jeggy/jellystructure:latest
    container_name: jellystructure
    user: "1000:1000"
    networks: [caddy]
    volumes:
      - /home/jeggy/IdeaProjects/jellystructure/config:/config   # keep existing state (F6/F8)
      - /mnt/media/jellyfin/movies:/mnt/media/jellyfin/movies    # identity mounts (F6)
      - /mnt/series/jellyfin:/mnt/series/jellyfin
    environment:
      CONFIG_FILE: /config/config.toml
      DB_FILE: /config/jellystructure.db
      FRONTEND_DIR: /app/frontend
      SERVER_PORT: 9505
      COOKIE_SECURE: "1"          # only once TLS is really terminated, else login breaks
      CORS_ALLOWED_ORIGINS: ""    # stays empty if Ravilo is same-origin via Caddy (F5)
    restart: unless-stopped
    labels:
      caddy: "js.example.net"
      caddy.reverse_proxy: "{{upstreams 9505}}"
      caddy.request_body.max_size: "64MB"

  ravilo-web:
    image: ghcr.io/jeggy/ravilo-web:latest
    container_name: ravilo-web
    networks: [caddy]
    restart: unless-stopped
    labels:
      caddy: "ravilo.example.net"
      # order matters: /api and /ws to the backend, everything else static (F5)
      caddy.0_handle: "/api/*"
      caddy.0_handle.reverse_proxy: "jellystructure:9505"
      caddy.1_handle: "/ws*"
      caddy.1_handle.reverse_proxy: "jellystructure:9505"
      caddy.2_handle: "/*"
      caddy.2_handle.reverse_proxy: "{{upstreams 80}}"
```

Two things to decide before this is real: whether the admin surface (`js.example.net`) should be on the public
internet at all — the security review's §6 suggested publishing only Ravilo's `/api/tv/**` and keeping
`/api/config`, `/api/media/**`, `/api/settings/**` on the LAN/VPN, which removes most of the blast radius for
free — and whether jellystructure should reach Jellyfin over the internal network (`http://jellyfin:8096`)
rather than the current `https://jellyfin.example.net`, which currently hairpins out through Caddy and back.

---

## Part 4 — Suggested order

| # | Work | Why here |
|---|---|---|
| 1 | `.dockerignore` (F2) | Do before any build/publish; stops secrets ever entering a layer |
| 2 | `libchromaprint-tools` (F1) | One line; without it the image silently loses a shipped feature |
| 3 | Fix CSP — fonts + `frame-src` (F3) | Already broken in production; independent of Docker |
| 4 | Dockerfile tidy: `--chmod`/`--chown`, `HEALTHCHECK` (F9) | Cheap, while in the file |
| 5 | `ravilo-web` Dockerfile (Part 3) | Second image |
| 6 | GHCR publish workflow + `docker login ghcr.io` (F7) | Tag-triggered `docker/build-push-action`, both images |
| 7 | Caddy rate-limit plugin (F4) | **Gate for public exposure** — separate repo, do before opening the firewall |
| 8 | Compose + identity mounts, cut over (F6/F8) | Debug→release switch; verify perf and that state carried over |
| 9 | Post-deploy checklist from the deployment guide | `COOKIE_SECURE`, firewall, secret rotation, CSP in a real browser |

**Secret rotation is still outstanding** from the 2026-08-02 review — `config.toml` was read off the running
server during that audit via the C2 bug, so the Jellyfin admin token, TMDB key, qBittorrent password and *arr
keys should be treated as burned. That should happen before public exposure regardless of the Docker work, and
I did not check whether it was ever done.

---

## Method

Read the Dockerfile, both compose files, CI, `AuthPlugin`/`AuthRoutes`/`Server.kt`, the Ravilo web entry point
and its wasm actuals, and both 2026-08-02 reports. Verified live against the running instance on
`127.0.0.1:9505`: C1 (`/%61pi/stats` → 401), C2 (both traversal routes → 404), H1 (`/ws` → 101 then an
immediate `1008 Not authenticated` close frame with zero payload — a plain `curl` showing `101` is misleading
here, the handshake completes before the handler runs), CSP/HSTS headers on both `/` and `/tv/`, and the
`wf.css` Google-Fonts `@import`. Built `:ravilo-web:wasmJsBrowserDistribution` to confirm the bundle is
self-contained. Checked `debian:bookworm-slim` in a throwaway container for `fpcalc`/`nice`/`ionice`, and the
live Caddy container's module list for a rate limiter. No code changed, nothing published, no live config or
media touched.

**Not covered:** dependency CVE scanning, the Jellyfin instance's own hardening, multi-arch builds (everything
assumes amd64), and whether the admin surface should be public at all — that's a decision, not a finding.
