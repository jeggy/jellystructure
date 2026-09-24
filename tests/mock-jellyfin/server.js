#!/usr/bin/env node
// Minimal Jellyfin API mock for Jellystructure CI.
// Covers: auth, library folders, item discovery, refresh endpoints.
"use strict";

const http = require("http");
const crypto = require("crypto");
const PORT = process.env.PORT ?? 8096;
// Phase 243 — the version this mock claims to be. Kept above the 12.0 floor deliberately: the
// suite should exercise the supported path, and a below-floor mock would put a permanent
// advisor finding into every e2e run.
//
// Phase 241 (FR-241-6) — this is also the mock's declared TARGET: every refusal below was measured
// against a real Jellyfin at this version, on the date named. When the household server moves, this
// constant is the marker that says the mock has not. Re-measured against the live server
// (jellyfin.example.net, 12.1.0) on 2026-09-20 — see VERIFIED_AGAINST.
const JELLYFIN_VERSION = "12.1.0";
const VERIFIED_AGAINST = { version: "12.1.0", date: "2026-09-20" };

// Phase 241 (FR-241-1/2) — the one credential this mock accepts, and the only form it accepts it in.
// `AuthenticateByName` mints exactly this string, and config-test/config.toml pre-sets it as
// `jellyfin_token`, so every backend call already carries it.
const VALID_TOKEN = "mock-access-token";

/**
 * Phase 241 (FR-241-1/2/3) — does this request carry a valid credential?
 *
 * Returns "valid" | "wrong" | "absent". The three-way distinction is the point: a handler that
 * accepts anything non-empty reintroduces, in a smaller form, exactly the blindness this phase exists
 * to remove.
 *
 * FR-241-3 — `Authorization: MediaBrowser … Token="…"` and nothing else. `x-emby-authorization` is
 * gone: measured on 12.1, `X-Emby-Token` no longer authenticates (403 on /socket with a VALID token,
 * 2026-09-20), and nothing in the product has ever sent the `x-emby-authorization` variant.
 */
function credentialOf(headers) {
  const auth = String(headers["authorization"] || "");
  if (!auth.startsWith("MediaBrowser ")) return "absent";
  const m = /\bToken="([^"]*)"/.exec(auth);
  if (!m || !m[1]) return "absent";
  return m[1] === VALID_TOKEN ? "valid" : "wrong";
}

// FR-241-2's carve-outs. `AuthenticateByName` is how a credential is obtained in the first place.
//
// FR-241-4 — /System/Info/Public is exempt **by measurement, not by convenience**: it answers with no
// credential at all on the real server (curl with no Authorization header -> 200 with the version
// body, jellyfin.example.net 12.1.0, 2026-09-20), and the product's own comment at
// JellyfinClient.kt:324 records that it "would 200 even for an invalid token". Keep the distinction:
// exempt-because-measured survives a reader who asks why; exempt-because-bootstrap does not.
//
// If a media or image route is ever added here it belongs in this list too, with its own measurement
// inline (both are genuinely anonymous on 12.1: 206 video/mp4 and 200 respectively). Putting one
// behind the guard instead would make this mock STRICTER than the real server, which fails in the
// opposite direction and hides a regression just as well.
const ANONYMOUS_ROUTES = new Set(["/Users/AuthenticateByName", "/System/Info/Public"]);
// A subtitle file (`/Videos/{id}/{msid}/Subtitles/{index}/0/Stream.vtt`) — measured on the household's
// 12.1.0, 2026-09-24: 200 text/vtt with NO credential, 200 with a wrong `apikey=`, and
// `Access-Control-Allow-Origin: *` on every answer (an OPTIONS preflight: 204, same header). The TV
// receiver (R285) fetches these from its own origin, so behind the guard the mock refused what the real
// server serves — the stricter-than-real failure described above.
const ANONYMOUS_ROUTE_PATTERNS = [/^\/Videos\/[^/]+\/[^/]+\/Subtitles\/\d+\/\d+\/Stream\.vtt$/];
const ADMIN_USER = process.env.JELLYFIN_USER ?? "admin";
const ADMIN_PASS = process.env.JELLYFIN_PASS ?? "password";
// Media mount point as seen by the app container
const MEDIA_ROOT = process.env.MEDIA_ROOT ?? "/media";

function send(res, status, body) {
  const json = JSON.stringify(body);
  res.writeHead(status, { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(json) });
  res.end(json);
}

function readBody(req) {
  return new Promise((resolve) => {
    const chunks = [];
    req.on("data", c => chunks.push(c));
    req.on("end", () => resolve(Buffer.concat(chunks).toString()));
  });
}

const LIBRARIES = [
  { Id: "movies-lib-id", Name: "Movies", CollectionType: "movies" },
  { Id: "tv-lib-id", Name: "TV Shows", CollectionType: "tvshows" },
];

const ITEMS = [
  {
    Id: "sintel-id",
    Name: "Sintel",
    Type: "Movie",
    ProductionYear: 2010,
    Path: `${MEDIA_ROOT}/movies/Sintel (2010)/Sintel (2010).mkv`,
    ProviderIds: { Tmdb: "10378" },
  },
  {
    Id: "bbb-id",
    Name: "Big Buck Bunny",
    Type: "Movie",
    ProductionYear: 2008,
    Path: `${MEDIA_ROOT}/movies/Big Buck Bunny (2008)/Big Buck Bunny (2008).mkv`,
    ProviderIds: {},
  },
  {
    Id: "tos-id",
    Name: "Tears of Steel",
    Type: "Series",
    ProductionYear: 2012,
    Path: `${MEDIA_ROOT}/tv/Tears of Steel`,
    ProviderIds: { Tmdb: "99999" },
  },
  {
    Id: "babel-id",
    Name: "Babel Fish",
    Type: "Series",
    ProductionYear: 2000,
    Path: `${MEDIA_ROOT}/tv/Babel Fish`,
    ProviderIds: {},
  },
];

// R285 CI (2026-09-24) — ONE item that negotiates like a real transcode, so the receiver's track paths
// (audio restream, text subtitles drawn from VTT, PGS burn-in and un-burn, caption size) can be driven
// end to end without a Samsung TV. Big Buck Bunny, because no other spec reads its item detail or
// negotiates it (Sintel stays a plain direct play for phase 248's own spec). Shapes are Jellyfin
// 12.1's: MediaStreams on the item, the indices in PlaybackInfo's JSON body, and a TranscodingUrl
// that carries AudioStreamIndex whether or not one was requested (measured, phase 253).
const TRACKS_ITEM_ID = "bbb-id";
const TRACKS_ITEM_STREAMS = [
  { Type: "Video", Index: 0, Codec: "h264" },
  { Type: "Audio", Index: 1, Codec: "ac3", Language: "eng", DisplayTitle: "English - Dolby Digital - 5.1", Channels: 6, IsDefault: true },
  { Type: "Audio", Index: 2, Codec: "aac", Language: "dan", DisplayTitle: "Dansk - AAC - Stereo", Channels: 2 },
  { Type: "Subtitle", Index: 3, Codec: "subrip", Language: "eng", DisplayTitle: "English", IsTextSubtitleStream: true },
  { Type: "Subtitle", Index: 4, Codec: "hdmv_pgs_subtitle", Language: "dan", DisplayTitle: "Dansk" },
];
const TRACKS_ITEM_VTT = "WEBVTT\n\n1\n00:00:00.000 --> 01:00:00.000\nMock subtitle line\n";

function sendText(res, status, type, body) {
  // Jellyfin answers subtitle files with Access-Control-Allow-Origin: * — the TV receiver fetches them
  // from its own origin, so the mock must too.
  res.writeHead(status, { "Content-Type": type, "Content-Length": Buffer.byteLength(body), "Access-Control-Allow-Origin": "*" });
  res.end(body);
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  const path = url.pathname;
  const method = req.method;

  // FR-241-2 — ONE shared guard, not a check per route. Every mocked route other than the carve-outs
  // in ANONYMOUS_ROUTES requires the credential form 12.1 actually authenticates. Before this phase
  // seven data routes checked nothing at all, so the e2e suite could not tell a product that sends a
  // correct credential from one that sends none — which is how phase 238's regression shipped green.
  if (!ANONYMOUS_ROUTES.has(path) && !ANONYMOUS_ROUTE_PATTERNS.some((re) => re.test(path))) {
    const cred = credentialOf(req.headers);
    if (cred !== "valid") {
      return send(res, 401, { message: cred === "absent" ? "Token is required." : "Invalid token." });
    }
  }

  // POST /Users/AuthenticateByName
  if (method === "POST" && path === "/Users/AuthenticateByName") {
    const body = await readBody(req);
    // Refuse what the real server refuses. Jellyfin answers 400 when the MediaBrowser Authorization
    // header of a sign-in lacks Client, Device, DeviceId or Version — first probed on production
    // 2026-09-17 against 10.11.11 (a Ravilo login without a Version was a 400 that the backend
    // reported as "Could not reach Jellyfin"), and **re-measured against 12.1.0 on 2026-09-20**, per
    // phase 241 dev-review item 4: full header + bad credentials -> 401, same header without Version
    // -> 400, no Authorization header at all -> 400. A lenient mock is how that shipped.
    // FR-241-3 — the `x-emby-authorization` fallback is gone. 12.1 does not authenticate it and the
    // product has never sent it.
    const auth = String(req.headers["authorization"] || "");
    const missing = ["Client", "Device", "DeviceId", "Version"].filter((k) => !new RegExp(`\\b${k}="[^"]+"`).test(auth));
    if (!auth.startsWith("MediaBrowser ") || missing.length) {
      return send(res, 400, { message: `Authorization header incomplete: ${missing.join(", ") || "scheme"}` });
    }
    let creds = {};
    try { creds = JSON.parse(body); } catch {}
    if (creds.Username !== ADMIN_USER || creds.Pw !== ADMIN_PASS) {
      return send(res, 401, { message: "Invalid credentials" });
    }
    return send(res, 200, {
      User: { Id: "user-id-1", Name: ADMIN_USER, Policy: { IsAdministrator: true } },
      AccessToken: "mock-access-token",
      ServerId: "mock-server-id",
    });
  }

  // GET /System/Info/Public — anonymous on the real server (measured 12.1.0, 2026-09-19), and the one
  // place phase 243 reads the Jellyfin version from. The mock reports a version above 243's floor so
  // the e2e suite exercises the supported path; the backend's testConnection 404'd here before this.
  if (method === "GET" && path === "/System/Info/Public") {
    return send(res, 200, {
      Id: "mock-server-id",
      ServerName: "mock-jellyfin",
      Version: JELLYFIN_VERSION,
      StartupWizardCompleted: true,
    });
  }

  // GET /Library/VirtualFolders
  if (method === "GET" && path === "/Library/VirtualFolders") {
    return send(res, 200, LIBRARIES);
  }

  // GET /Items  (discovery)
  if (method === "GET" && path === "/Items") {
    return send(res, 200, { Items: ITEMS, TotalRecordCount: ITEMS.length });
  }

  // POST /Library/Refresh
  if (method === "POST" && path === "/Library/Refresh") {
    return send(res, 204, {});
  }

  // Phase 248 — the minimum PlaybackService.startPlayback() needs for a real cast test: item detail,
  // a direct-play PlaybackInfo answer, and fire-and-forget session bookkeeping. No media bytes are
  // ever served — the cast test's fake AVPlay records the stream URL but never fetches it.
  const itemMatch = path.match(/^\/Items\/([^/]+)$/);
  if (method === "GET" && itemMatch) {
    const item = ITEMS.find((i) => i.Id === itemMatch[1]);
    return send(res, 200, {
      Id: itemMatch[1],
      Name: item?.Name ?? itemMatch[1],
      RunTimeTicks: 60_000_000_0, // 60s, arbitrary — nothing plays a real stream in this suite
      UserData: { PlaybackPositionTicks: 0, Played: false, IsFavorite: false },
      MediaStreams: itemMatch[1] === TRACKS_ITEM_ID ? TRACKS_ITEM_STREAMS : [],
    });
  }
  const playbackInfoMatch = path.match(/^\/Items\/([^/]+)\/PlaybackInfo$/);
  if (method === "POST" && playbackInfoMatch) {
    const id = playbackInfoMatch[1];
    if (id === TRACKS_ITEM_ID) {
      let asked = {};
      try { asked = JSON.parse((await readBody(req)) || "{}"); } catch { /* a malformed body negotiates the defaults */ }
      const audio = Number.isInteger(asked.AudioStreamIndex) ? asked.AudioStreamIndex : 1;
      const sub = asked.SubtitleStreamIndex;
      const burn = Number.isInteger(sub) && TRACKS_ITEM_STREAMS.some((s) => s.Index === sub && s.Codec === "hdmv_pgs_subtitle");
      const transcodingUrl = `/videos/${id}/master.m3u8?MediaSourceId=${id}&VideoCodec=h264&AudioCodec=aac` +
        `&AudioStreamIndex=${audio}` + (burn ? `&SubtitleStreamIndex=${sub}&SubtitleMethod=Encode` : "") +
        `&PlaySessionId=mock-play-session-${id}&ApiKey=mock`;
      return send(res, 200, {
        MediaSources: [{ Id: id, Container: "mkv", SupportsDirectPlay: false, SupportsDirectStream: false, SupportsTranscoding: true,
          TranscodingUrl: transcodingUrl, TranscodingSubProtocol: "hls", MediaStreams: TRACKS_ITEM_STREAMS }],
        PlaySessionId: "mock-play-session-" + id,
      });
    }
    return send(res, 200, {
      MediaSources: [{ Id: id, Container: "mkv", SupportsDirectPlay: true, SupportsDirectStream: true, SupportsTranscoding: false, MediaStreams: [] }],
      PlaySessionId: "mock-play-session-" + id,
    });
  }
  const vttMatch = path.match(/^\/Videos\/([^/]+)\/[^/]+\/Subtitles\/(\d+)\/\d+\/Stream\.vtt$/);
  if (method === "GET" && vttMatch && vttMatch[1] === TRACKS_ITEM_ID) {
    return sendText(res, 200, "text/vtt; charset=utf-8", TRACKS_ITEM_VTT);
  }
  if (method === "POST" && (path === "/Sessions/Playing" || path === "/Sessions/Playing/Progress" || path === "/Sessions/Playing/Stopped")) {
    return send(res, 204, {});
  }

  // POST /Items/{id}/Refresh
  if (method === "POST" && /^\/Items\/[^/]+\/Refresh$/.test(path)) {
    return send(res, 204, {});
  }

  // GET /Users/Me  (optional, called by some auth flows)
  if (method === "GET" && path === "/Users/Me") {
    return send(res, 200, { Id: "user-id-1", Name: ADMIN_USER, Policy: { IsAdministrator: true } });
  }

  // GET /LiveTv/Info and /LiveTv/Channels — jellystructure's LiveTvService.sync() calls both
  // unconditionally at startup; mocking clean 200s instead of a 404 keeps CI logs quiet.
  if (method === "GET" && path === "/LiveTv/Info") {
    return send(res, 200, { IsEnabled: false });
  }
  if (method === "GET" && path === "/LiveTv/Channels") {
    return send(res, 200, { Items: [], TotalRecordCount: 0 });
  }

  send(res, 404, { message: "endpoint not mocked" });
}).listen(PORT, () => {
  // FR-241-6 — say it out loud in the CI log, so "which Jellyfin does this suite claim to be?" is
  // answerable from a run's output rather than by reading this file.
  console.log(
    `[jellyfin-mock] listening on :${PORT} — emulating Jellyfin ${JELLYFIN_VERSION} ` +
    `(refusals verified against ${VERIFIED_AGAINST.version} on ${VERIFIED_AGAINST.date})`
  );
});

// FR-241-5 — what actually opens this socket today is JellyfinSessionBridge (phase 110), one per
// connected TV, not the long-deleted JellyfinLibraryListener the old comment named. Each bridge
// registers the device's remote-control capabilities and carries Jellyfin dashboard commands
// (DisplayMessage / Play / Playstate) to that TV.
//
// FR-241-1 — this handler used to return 101 to ANY handshake, with no token check whatsoever. That
// is why the suite could never have caught phase 238's regression: on 12.1 the product's
// `/socket?api_key=<token>` is refused with 403 by the real server, and accepted with 101 by this
// mock. Measured live 2026-09-20 (jellyfin.example.net, 12.1.0), with a REAL server token:
//
//   no credential          403        api_key=<valid>        403
//   api_key=<bogus>        403        apikey=<valid>         101
//   apikey=<bogus>         403        Authorization header   101   <- what 238 ships
//   Authorization bogus    403        X-Emby-Token=<valid>   403
//
// So: valid credential in the header form -> 101, everything else -> 403. `deviceId` stays a query
// parameter and is deliberately ignored here — it is not a credential.
server.on("upgrade", (req, socket) => {
  const cred = credentialOf(req.headers);
  if (cred !== "valid") {
    socket.write(
      "HTTP/1.1 403 Forbidden\r\n" +
      "Connection: close\r\n" +
      "Content-Length: 0\r\n\r\n"
    );
    socket.destroy();
    return;
  }
  const key = req.headers["sec-websocket-key"];
  const accept = crypto.createHash("sha1").update(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").digest("base64");
  socket.write(
    "HTTP/1.1 101 Switching Protocols\r\n" +
    "Upgrade: websocket\r\n" +
    "Connection: Upgrade\r\n" +
    `Sec-WebSocket-Accept: ${accept}\r\n\r\n`
  );
});
