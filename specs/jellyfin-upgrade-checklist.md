# Jellyfin upgrade checklist

**Run this after every Jellyfin *major* upgrade, before assuming anything still works.**
Phase 243 declared the floor (12.0+) and the no-compatibility-branches rule; this is the operational
half — what to actually check when the server underneath moves.

The worked example throughout is the household's **10.11.11 → 12.1.0** upgrade (audited 2026-09-18).
Three route-level behaviours changed at once, one of them broke a live feature *silently for hours*,
and nothing announced any of it — because nothing was watching. Every step below is something that
audit had to discover by hand.

Each step names the **manual** form first, because that is what works today, and the **automation**
that replaces it where one exists. A checklist that can only be run after another phase ships is a
checklist that will not be run.

---

## 0. Note the version, before and after

```bash
curl -s http://<jellyfin>/System/Info/Public | jq -r .Version
```

Anonymous — no credential needed, and it still answers when authentication is broken, which is
exactly when you will want it.

**Automated:** phase 243. `GET /api/health` now reports `jellyfin_version`, and `/api/health/full`
carries a **Jellyfin version** check that fails below the floor. After the upgrade:

```bash
curl -s http://localhost:9505/api/health | jq -r .jellyfin_version
```

If that reads `null`, nothing has successfully probed Jellyfin since the backend started — which is
itself a finding, not a formatting problem.

---

## 1. The authentication form matrix

**This is the step that matters most.** 12.1 changed two credential forms at once and answered
plausibly to both of the old ones.

Run each of these and record the status code:

| Form | How | 10.11.11 | 12.1.0 |
|---|---|---|---|
| `Authorization: MediaBrowser Token="…"` | header | 200 | **200** |
| `X-Emby-Token: …` | header | 200 | **401** |
| `?api_key=…` | query | 200 | **401** |
| `?apikey=…` | query | 200 | **200** |
| `Authorization` with no `Version="…"` | header | 400 | **400** |

```bash
T=<token>; B=http://<jellyfin>
for h in "Authorization: MediaBrowser Token=\"$T\", Client=\"probe\", Device=\"probe\", DeviceId=\"probe\", Version=\"1\"" \
         "X-Emby-Token: $T"; do
  printf '%-30s %s\n' "${h%%:*}" "$(curl -s -o /dev/null -w '%{http_code}' -H "$h" "$B/Users")"
done
printf '%-30s %s\n' "api_key"  "$(curl -s -o /dev/null -w '%{http_code}' "$B/Items?api_key=$T")"
printf '%-30s %s\n' "apikey"   "$(curl -s -o /dev/null -w '%{http_code}' "$B/Items?apikey=$T")"
```

⚠ **A 200 here does not prove the credential was accepted.** Many Jellyfin routes answer
*anonymously*. Test with a **deliberately invalid** token too: if the bad token also gets 200, the
route is anonymous and tells you nothing about the credential form. That distinction is what
phases 239 and 240 are built on, and getting it wrong is how the audit misread its own results at
first.

**Automated:** the auth matrix lives in `JellyfinIdentityHeaderTest`; the live half is phase 240.

---

## 2. The WebSocket handshake

`/socket` is not covered by anything that tests HTTP routes, and it is what broke.

```bash
# 12.1.0: api_key → 403;  apikey → 101;  Authorization header → 101
curl -s -o /dev/null -w '%{http_code}\n' -H "Connection: Upgrade" -H "Upgrade: websocket" \
     -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: $(head -c16 /dev/urandom | base64)" \
     "http://<jellyfin>/socket?api_key=<token>"
```

Phase 110's Jellyfin session bridge rides this socket. When it cannot open, you lose dashboard
pause/seek and remote control — with **no error anywhere**, which is the whole reason phase 238
exists.

**Automated:** phase 238's per-device bridge state in `/api/health/full`, and phase 240's
WebSocket probe. Check the bridge block after the upgrade even if everything else looks fine.

---

## 3. Route shapes: does the endpoint still exist?

Do **not** probe with an invalid token expecting 401 to mean "the route exists" — many routes
authenticate late, so the request *executes*, and that is how the 2026-09-18 audit accidentally
restarted the household's Jellyfin.

Use a **method the route does not implement** instead. ASP.NET Core answers **405** for a method
mismatch and **404** for an absent path, it needs no credential, and it mutates nothing:

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X PROPFIND "http://<jellyfin>/System/Restart"   # 405 = exists
curl -s -o /dev/null -w '%{http_code}\n' -X PROPFIND "http://<jellyfin>/Nope/NotARoute"   # 404 = gone
```

**Automated:** phase 240's live route guard.

---

## 4. Model fields: did anything get renamed?

`ignoreUnknownKeys` means a **renamed** Jellyfin field costs you nothing visible — the property
silently takes its Kotlin default forever. A field with no default fails loudly; a field with one
fails silently, and almost all of them have one.

By hand, for the fields you actually depend on:

```bash
curl -s -H "Authorization: MediaBrowser Token=\"$T\", Client=\"p\", Device=\"p\", DeviceId=\"p\", Version=\"1\"" \
     "http://<jellyfin>/System/Info" | jq 'keys'
```

…and compare against the `@SerialName` values in `src/linuxX64Main/kotlin/dev/jellystructure/auth/Models.kt`.

Phase 246 found exactly this shape live: `TranscodingTempPath` read `/transcode` before the upgrade
and `/cache/transcodes` after it, and printing the unset value as "(default)" is what hid the move.

**Automated:** phase 240's model-field check.

---

## 5. The OpenAPI document

`GET /api-docs/openapi.json` returned **500** on 12.1.0. It was the reference the audit expected to
use and it was gone, which is worth knowing *before* you plan an investigation around it.

```bash
curl -s -o /dev/null -w '%{http_code}\n' "http://<jellyfin>/api-docs/openapi.json"
```

If it 500s, the live probes above are the reference. Do not infer route shapes from an older
version's document.

---

## 6. Re-stamp what you verified

Phase 243 FR-243-5: every comment recording "verified against Jellyfin X" states the version **and
the date**. Find the notes that are now stale:

```bash
grep -rn '10\.11\.1[0-9]\|10\.11\.x' --include='*.kt' --include='*.kts' --include='*.js' \
  . | grep -v '/build/'
```

Either re-verify against the new server and re-stamp, or mark the note historical with the reason it
is still worth keeping. The load-bearing one is `JellyfinClient.kt`'s `Version`-header rule, which
`tests/mock-jellyfin/server.js` cites to justify its only real refusal — re-stamp that one first.

---

## 7. Make the mock match

Phase 241: the mock Jellyfin refuses what the real one refuses. If step 1 changed a credential form,
`tests/mock-jellyfin/server.js` must change with it — otherwise the e2e suite keeps passing against
a server that no longer exists, which is how a real regression reaches production green.

`JELLYFIN_VERSION` in that file should also be moved to the version you are now running.

---

## 8. Finally, watch a real playback

Nothing above plays a frame. Start something on a real TV and confirm: it starts, the dashboard sees
the session, pause from the dashboard works (that is the step 2 socket), and subtitles still select.
