# Public-internet deployment guide

Companion to [`backend-security-review-2026-08-02.md`](backend-security-review-2026-08-02.md). That report's
21 in-app findings (everything except **D1**, the FD-exhaustion DoS) are now fixed in code. This doc covers
the remaining piece: **D1 is not fixable in-process** (Kotlin/Native's CIO engine hard-aborts the process at
FD ≥ 1024 — upstream KTOR-8703) — a reverse proxy in front of the Kotlin process is not optional for a
public-internet deployment, it is the mitigation for D1.

This assumes nginx; the same knobs exist in Caddy/Traefik under different names.

## 1. New environment variables (read this before flipping anything public)

Two env vars were added by the fixes and both default to the safe-for-LAN, **not-yet-safe-for-internet**
value. Both must be set explicitly before this goes public:

| Var | Default | Set to | Why |
|---|---|---|---|
| `COOKIE_SECURE` | `0` | `1` | Without it the `js_session` admin cookie has no `Secure` flag and will be sent over plain HTTP if anything downgrades the connection (M2). Only set `1` once TLS is actually terminated in front of the app — with `1` and no HTTPS, login silently stops working (browsers drop `Secure` cookies over `http://`). |
| `CORS_ALLOWED_ORIGINS` | `""` (no extra origins) | comma-separated list of the real origin(s) the admin frontend/Ravilo web client are served from, e.g. `https://js.example.com` | CORS now only reflects explicitly-listed origins instead of any caller (M1). Leaving this empty is fine if nothing cross-origin needs to call the API with credentials. |

Both are read via `dev.jellystructure.env(name, default)` (`Main.kt:346`) — same mechanism as every other
config env var in this app, set them however the process is started (systemd `Environment=`, docker
`-e`, etc).

## 2. nginx in front of the Kotlin process

```nginx
# /etc/nginx/sites-available/jellystructure
limit_req_zone $binary_remote_addr zone=js_login:10m rate=5r/m;
limit_req_zone $binary_remote_addr zone=js_api:10m rate=30r/s;
limit_conn_zone $binary_remote_addr zone=js_conn:10m;

server {
    listen 443 ssl http2;
    server_name js.example.com;

    ssl_certificate     /etc/letsencrypt/live/js.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/js.example.com/privkey.pem;

    # --- D1: connection caps well below the FD-watchdog's 900 shed threshold ---
    # Kotlin's FdWatchdog warns at 700 / sheds at 900 / restarts at 980 (ops/FdWatchdog.kt).
    # Keep nginx's own ceiling well under 700 so the proxy — not the app — absorbs a flood.
    limit_conn js_conn 20;          # per-IP concurrent connections
    limit_req  zone=js_api burst=20 nodelay;

    # tighter limit specifically on the login endpoints, on top of the app's own
    # in-process LoginRateLimiter (5 attempts / 60s per key) — belt and suspenders,
    # since the app-level limiter only sees X-Forwarded-For if this block sets it (§3).
    location ~ ^/(api/auth/login|api/tv/login)$ {
        limit_req zone=js_login burst=3 nodelay;
        proxy_pass http://127.0.0.1:9505;
        include /etc/nginx/proxy_params_jellystructure;
    }

    # M6: belt-and-suspenders body cap in front of the app's own Content-Length check
    # (the app can't see chunked-encoding bodies that omit Content-Length; nginx can
    # enforce this one at the transport level regardless of how the body is framed).
    client_max_body_size 64m;

    location / {
        proxy_pass http://127.0.0.1:9505;
        include /etc/nginx/proxy_params_jellystructure;
    }

    # /ws is a long-lived WebSocket (H1 now requires the js_session cookie on connect,
    # but it's still worth its own connection accounting since it's held open).
    location /ws {
        proxy_pass http://127.0.0.1:9505;
        include /etc/nginx/proxy_params_jellystructure;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;
    }
}

server {
    listen 80;
    server_name js.example.com;
    return 301 https://$host$request_uri;
}
```

```nginx
# /etc/nginx/proxy_params_jellystructure
proxy_set_header Host $host;
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_redirect off;
```

**C1 caution (do not touch path handling here):** the path-decoding auth bypass (C1) was fixed **in the
app** (`AuthPlugin.decodeRoutingPath`, decodes each path segment identically to how Ktor's router decodes
it before checking auth tiers). Do **not** add `merge_slashes off`, custom `rewrite` rules, or any path
normalization in nginx that changes how `%2F`/double-slashes reach the app — mismatched normalization
between the proxy and the app is exactly the shape of bug C1 was. Pass the path through unmodified.

## 3. `X-Forwarded-For` trust

`LoginRateLimiter.clientKey()` (`auth/LoginRateLimiter.kt`) reads `X-Forwarded-For` to key its 5-attempts/60s
window per client. That header is **only trustworthy from a proxy you control** — the nginx config above sets
it correctly via `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for`. If the Kotlin process is ever
reachable directly (firewall misconfiguration, proxy bypass), a client can forge `X-Forwarded-For` and dodge
rate limiting entirely — another reason §4's firewall step isn't optional.

## 4. Firewall: the Kotlin process must not be reachable directly

```
# only nginx (localhost) and admin SSH may reach the app port; nothing else
ufw deny 9505
```

Bind the app to `127.0.0.1:9505` (or a private interface) rather than `0.0.0.0` if the deployment allows it,
so a firewall misconfiguration fails closed rather than open.

## 5. Post-deploy checklist

- [ ] `COOKIE_SECURE=1` set, and confirmed TLS is actually terminated (log in over `https://`, check the
      `Set-Cookie` response header has `Secure`)
- [ ] `CORS_ALLOWED_ORIGINS` set to the real origin(s), or left empty if nothing cross-origin is needed
- [ ] nginx config above deployed, `nginx -t` clean, reloaded
- [ ] Firewall confirmed: `curl http://<host>:9505/api/health` from outside the host fails; the same request
      via `https://js.example.com/api/health` succeeds
- [ ] **Rotate every secret in `config.toml`** (Jellyfin token, TMDB key, qBittorrent/Radarr/Sonarr/Seerr
      keys) if this hasn't already been done — they were read off the running server during the security
      audit (see the report's "Do this first" section) and must be treated as burned regardless of when
      public exposure actually happens
- [ ] CSP header (M8, `Server.kt`) has **not** been verified against a real browser session — load the admin
      frontend and Ravilo web client through the proxy and check the browser console for CSP violations
      before relying on it
- [ ] Confirm `FdWatchdog` alert/shed logging is wired to something you'll actually see (D1's proxy-side
      mitigation reduces the chance of hitting this, it doesn't eliminate it)

## What's still not covered

- **DNS-rebinding SSRF** (noted as a known gap in `UrlSafety.kt`'s doc comment) — the guard is a literal-host
  check on the URL as given, not a resolve-then-validate-the-connecting-IP check. A determined attacker with
  control of a domain's DNS could still get one first request through to a private address before the record
  changes. Closing this fully needs a custom resolver/connect-time hook into the Curl-backed HTTP client —
  scoped as a follow-up, not done here.
- **Dependency CVE scanning** and the **Jellyfin instance's own hardening** were both explicitly out of scope
  for the original review and remain so here.
