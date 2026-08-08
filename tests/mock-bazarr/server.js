#!/usr/bin/env node
// Minimal Bazarr API mock for Jellystructure CI (phase 157).
// Covers everything BazarrClient.kt calls: ping, movies/series/episodes listing, wanted, history,
// providers, language profiles, task trigger, and every subtitle command endpoint. Shapes match a
// real Bazarr 1.6.0 instance, verified live during the phase-157 dev-review addendum — see
// specs/requirements/phase-157-bazarr-subtitles.md.
"use strict";

const http = require("http");
const PORT = process.env.PORT ?? 6767;
const API_KEY = process.env.BAZARR_API_KEY ?? "mock-bazarr-key";

function send(res, status, body) {
  const json = JSON.stringify(body);
  res.writeHead(status, { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(json) });
  res.end(json);
}

// One movie/series/episode per Jellyfin mock fixture (tests/mock-jellyfin/server.js), so a real
// scan-and-match round-trip resolves cleanly: imdbId/tvdbId are made up but stable, radarrId/
// sonarrId/sonarrEpisodeId are arbitrary small ints (Bazarr's own primary keys, unrelated to
// Jellyfin/TMDB ids).
const MOVIES = [
  {
    title: "Sintel", path: "/media/movies/Sintel (2010)/Sintel (2010).mkv",
    imdbId: "tt1727587", radarrId: 1, monitored: true, profileId: 1,
    missing_subtitles: [{ name: "Danish", code2: "da", code3: "dan", forced: false, hi: false }],
    subtitles: [{ name: "English", code2: "en", path: "/media/movies/Sintel (2010)/Sintel (2010).en.srt", forced: false, hi: false, file_size: 1024 }],
  },
  {
    title: "Big Buck Bunny", path: "/media/movies/Big Buck Bunny (2008)/Big Buck Bunny (2008).mkv",
    imdbId: "tt1254207", radarrId: 2, monitored: true, profileId: 1,
    missing_subtitles: [], subtitles: [],
  },
];

const SERIES = [
  {
    title: "Tears of Steel", path: "/media/tv/Tears of Steel",
    tvdbId: 262980, sonarrSeriesId: 1, monitored: true, profileId: 1, episodeMissingCount: 1,
  },
  {
    title: "Babel Fish", path: "/media/tv/Babel Fish",
    tvdbId: 999999, sonarrSeriesId: 2, monitored: true, profileId: 1, episodeMissingCount: 0,
  },
];

const EPISODES = {
  1: [
    {
      title: "Episode 1", path: "/media/tv/Tears of Steel/S01E01.mkv", season: 1, episode: 1,
      sonarrSeriesId: 1, sonarrEpisodeId: 101, monitored: true,
      missing_subtitles: [{ name: "Danish", code2: "da", code3: "dan", forced: false, hi: false }],
      subtitles: [],
    },
  ],
  2: [
    {
      title: "Episode 1", path: "/media/tv/Babel Fish/S01E01.mkv", season: 1, episode: 1,
      sonarrSeriesId: 2, sonarrEpisodeId: 201, monitored: true,
      missing_subtitles: [], subtitles: [{ name: "English", code2: "en", forced: false, hi: false }],
    },
  ],
};

const PROVIDERS = [
  { name: "opensubtitlescom", status: "Good", retry: "-" },
  { name: "yifysubtitles", status: "Good", retry: "-" },
];

const PROFILES = [
  {
    profileId: 1, name: "Standard profile", cutoff: null,
    items: [
      { id: 1, language: "en", hi: "False", forced: "False" },
      { id: 2, language: "da", hi: "False", forced: "False" },
    ],
  },
];

const TASKS = [
  { job_id: "wanted_search_missing_subtitles_movies", name: "Search for Missing Movies Subtitles", running: false },
  { job_id: "wanted_search_missing_subtitles_series", name: "Search for Missing Series Subtitles", running: false },
  { job_id: "movies_full_scan_subtitles", name: "Index All Existing Movies Subtitles", running: false },
  { job_id: "series_full_scan_subtitles", name: "Index All Existing Episodes Subtitles", running: false },
  { job_id: "upgrade_subtitles", name: "Upgrade Previously Downloaded Subtitles", running: false },
];

const HISTORY = [
  { action: 0, language: "en", provider: "opensubtitlescom", score: "100", timestamp: "2026-08-01 10:00:00", description: "Downloaded" },
];

function checkAuth(req, res) {
  if (req.headers["x-api-key"] !== API_KEY) {
    send(res, 401, { error: "Unauthorized" });
    return false;
  }
  return true;
}

function paged(items, url) {
  const start = parseInt(url.searchParams.get("start") ?? "0", 10);
  const lengthParam = url.searchParams.get("length");
  const length = lengthParam == null ? -1 : parseInt(lengthParam, 10);
  const data = length < 0 ? items : items.slice(start, start + length);
  return { data, total: items.length };
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  const path = url.pathname;
  const method = req.method;

  if (path === "/api/system/status" && method === "GET") {
    if (!checkAuth(req, res)) return;
    return send(res, 200, { bazarr_version: "1.6.0" });
  }
  if (!checkAuth(req, res)) return;

  if (path === "/api/movies" && method === "GET") return send(res, 200, paged(MOVIES, url));
  if (path === "/api/series" && method === "GET") return send(res, 200, paged(SERIES, url));
  if (path === "/api/episodes" && method === "GET") {
    const seriesId = parseInt(url.searchParams.get("seriesid[]") ?? "0", 10);
    return send(res, 200, paged(EPISODES[seriesId] ?? [], url));
  }
  if (path === "/api/movies/wanted" && method === "GET") {
    const wanted = MOVIES.filter((m) => m.missing_subtitles.length > 0)
      .map((m) => ({ title: m.title, radarrId: m.radarrId, missing_subtitles: m.missing_subtitles }));
    return send(res, 200, paged(wanted, url));
  }
  if (path === "/api/episodes/wanted" && method === "GET") {
    const wanted = Object.values(EPISODES).flat().filter((e) => e.missing_subtitles.length > 0)
      .map((e) => ({
        seriesTitle: SERIES.find((s) => s.sonarrSeriesId === e.sonarrSeriesId)?.title ?? "",
        episodeTitle: e.title, episode_number: `${e.season}x${e.episode}`,
        sonarrSeriesId: e.sonarrSeriesId, sonarrEpisodeId: e.sonarrEpisodeId, missing_subtitles: e.missing_subtitles,
      }));
    return send(res, 200, paged(wanted, url));
  }
  if (path === "/api/providers" && method === "GET") return send(res, 200, { data: PROVIDERS });
  if (path === "/api/system/languages/profiles" && method === "GET") return send(res, 200, PROFILES);
  if (path === "/api/movies/history" && method === "GET") return send(res, 200, paged(HISTORY, url));
  if (path === "/api/episodes/history" && method === "GET") return send(res, 200, paged(HISTORY, url));
  if (path === "/api/system/tasks" && method === "GET") return send(res, 200, { data: TASKS });
  if (path === "/api/system/tasks" && method === "POST") return send(res, 204, {});

  // Command endpoints — search/download/upload/delete/sync/actions. Tests only assert the overview
  // and per-title read state, not the actual outcome of a command, so these just ack success.
  if (path === "/api/movies/subtitles" && ["PATCH", "POST", "DELETE"].includes(method)) return send(res, 204, {});
  if (path === "/api/episodes/subtitles" && ["PATCH", "POST", "DELETE"].includes(method)) return send(res, 204, {});
  if (path === "/api/subtitles" && method === "PATCH") return send(res, 204, {});
  if (path === "/api/providers/movies" && method === "GET") return send(res, 200, []);
  if (path === "/api/providers/episodes" && method === "GET") return send(res, 200, []);
  if (path === "/api/providers/movies" && method === "POST") return send(res, 204, {});
  if (path === "/api/providers/episodes" && method === "POST") return send(res, 204, {});
  if (path === "/api/movies" && method === "PATCH") return send(res, 204, {});
  if (path === "/api/series" && method === "PATCH") return send(res, 204, {});

  send(res, 404, { message: "endpoint not mocked" });
});

server.listen(PORT, () => {
  console.log(`[bazarr-mock] listening on :${PORT}`);
});
