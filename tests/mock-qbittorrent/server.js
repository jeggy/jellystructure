// Minimal qBittorrent Web API mock for the e2e stack (phase 263).
//
// Phase 254's "Replace from clean copy" looks for a damaged library file's clean copy among the torrents
// qBittorrent reports (same basename, inside the torrent's content path, a different inode). Before this
// mock the e2e stack had no qBittorrent at all, so that path had never run outside a production library.
// It serves only what QBittorrentClient reads: sign-in and the torrent list. The one torrent's content path
// is where scripts/build-fixtures.sh puts the seeding copy of "Two Tongues (2021)" — outside every library
// root, seen at the same path by the app (FIXTURE_DIR is /media there).
const http = require("http");

const PORT = Number(process.env.PORT ?? 8080);
const USER = process.env.QB_USER ?? "admin";
const PASS = process.env.QB_PASS ?? "adminadmin";
const SID = "mock-qbittorrent-sid";
const MEDIA_ROOT = process.env.MEDIA_ROOT ?? "/media";

const TORRENTS = [
  {
    hash: "2b0c1f1e7a3d4c5b6a7980e1f2a3b4c5d6e7f809",
    name: "Two.Tongues.2021.1080p",
    state: "uploading",
    save_path: `${MEDIA_ROOT}/seeding`,
    content_path: `${MEDIA_ROOT}/seeding/Two.Tongues.2021.1080p`,
    tracker: "",
    ratio: 1.5,
    num_seeds: 0,
    num_leechs: 0,
    uploaded: 0,
    size: 0,
    added_on: 1757000000,
  },
];

function send(res, status, body, headers = {}) {
  const text = typeof body === "string" ? body : JSON.stringify(body);
  res.writeHead(status, { "Content-Type": typeof body === "string" ? "text/plain" : "application/json", "Content-Length": Buffer.byteLength(text), ...headers });
  res.end(text);
}

function readBody(req) {
  return new Promise((resolve) => {
    const chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => resolve(Buffer.concat(chunks).toString()));
  });
}

http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);

  // The real server answers the version anonymously; used here as the healthcheck.
  if (req.method === "GET" && url.pathname === "/api/v2/app/version") return send(res, 200, "v5.0.0");

  if (req.method === "POST" && url.pathname === "/api/v2/auth/login") {
    const form = new URLSearchParams(await readBody(req));
    if (form.get("username") !== USER || form.get("password") !== PASS) return send(res, 200, "Fails.");
    return send(res, 200, "Ok.", { "Set-Cookie": `SID=${SID}; HttpOnly; path=/` });
  }

  // Everything else needs the session, as on the real server (403 without it).
  if (!String(req.headers.cookie ?? "").includes(`SID=${SID}`)) return send(res, 403, "Forbidden");

  if (req.method === "GET" && url.pathname === "/api/v2/torrents/info") return send(res, 200, TORRENTS);
  if (req.method === "GET" && url.pathname === "/api/v2/transfer/speedLimitsMode") return send(res, 200, "0");
  if (req.method === "POST" && url.pathname === "/api/v2/transfer/setSpeedLimitsMode") return send(res, 200, "");

  return send(res, 404, "Not Found");
}).listen(PORT, () => console.log(`[mock-qbittorrent] listening on :${PORT}`));
