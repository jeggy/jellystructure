#!/usr/bin/env node
// Minimal Jellyfin API mock for Jellystructure CI.
// Covers: auth, library folders, item discovery, refresh endpoints.
"use strict";

const http = require("http");
const PORT = process.env.PORT ?? 8096;
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

http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  const path = url.pathname;
  const method = req.method;

  // POST /Users/AuthenticateByName
  if (method === "POST" && path === "/Users/AuthenticateByName") {
    const body = await readBody(req);
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

  // POST /Items/{id}/Refresh
  if (method === "POST" && /^\/Items\/[^/]+\/Refresh$/.test(path)) {
    return send(res, 204, {});
  }

  // GET /Users/Me  (optional, called by some auth flows)
  if (method === "GET" && path === "/Users/Me") {
    return send(res, 200, { Id: "user-id-1", Name: ADMIN_USER, Policy: { IsAdministrator: true } });
  }

  send(res, 404, { message: "endpoint not mocked" });
}).listen(PORT, () => {
  console.log(`[jellyfin-mock] listening on :${PORT}`);
});
