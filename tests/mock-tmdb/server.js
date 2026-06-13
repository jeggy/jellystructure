#!/usr/bin/env node
// Minimal TMDB v3 mock for Jellystructure CI.
// Handles search/movie, movie/{id}, search/tv, tv/{id} for the three test fixtures.
"use strict";

const http = require("http");
const PORT = process.env.PORT ?? 3001;

const MOVIES = {
  10378: {
    id: 10378, title: "Sintel", original_title: "Sintel",
    original_language: "en", release_date: "2010-09-30",
    overview: "A lonely young woman, Sintel, helps and befriends a dragon.",
    poster_path: null, backdrop_path: null,
    genres: [{ id: 16, name: "Animation" }],
  },
  10009: {
    id: 10009, title: "Big Buck Bunny", original_title: "Big Buck Bunny",
    original_language: "en", release_date: "2008-04-10",
    overview: "A large, well-tempered rabbit deals with three bullying rodents.",
    poster_path: null, backdrop_path: null,
    genres: [{ id: 16, name: "Animation" }],
  },
};

const TV = {
  99999: {
    id: 99999, name: "Tears of Steel", original_name: "Tears of Steel",
    original_language: "en", first_air_date: "2012-09-26",
    overview: "A group of warriors and scientists take refuge in Amsterdam.",
    poster_path: null, backdrop_path: null,
    genres: [{ id: 878, name: "Science Fiction" }],
  },
};

const SEARCH_MOVIES = [
  { id: 10378, title: "Sintel", release_date: "2010-09-30", original_language: "en", overview: "..." },
  { id: 10009, title: "Big Buck Bunny", release_date: "2008-04-10", original_language: "en", overview: "..." },
];

const SEARCH_TV = [
  { id: 99999, name: "Tears of Steel", first_air_date: "2012-09-26" },
];

function send(res, status, body) {
  const json = JSON.stringify(body);
  res.writeHead(status, { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(json) });
  res.end(json);
}

http.createServer((req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  const path = url.pathname;

  // /search/movie
  if (path === "/search/movie") {
    const q = (url.searchParams.get("query") ?? "").toLowerCase();
    const results = SEARCH_MOVIES.filter(m => m.title.toLowerCase().includes(q));
    return send(res, 200, { results });
  }

  // /movie/{id}
  const movieMatch = path.match(/^\/movie\/(\d+)$/);
  if (movieMatch) {
    const movie = MOVIES[Number(movieMatch[1])];
    if (!movie) return send(res, 404, { status_message: "not found" });
    return send(res, 200, movie);
  }

  // /search/tv
  if (path === "/search/tv") {
    const q = (url.searchParams.get("query") ?? "").toLowerCase();
    const results = SEARCH_TV.filter(s => s.name.toLowerCase().includes(q));
    return send(res, 200, { results });
  }

  // /tv/{id}
  const tvMatch = path.match(/^\/tv\/(\d+)$/);
  if (tvMatch) {
    const show = TV[Number(tvMatch[1])];
    if (!show) return send(res, 404, { status_message: "not found" });
    return send(res, 200, show);
  }

  send(res, 404, { status_message: "endpoint not mocked" });
}).listen(PORT, () => {
  console.log(`[tmdb-mock] listening on :${PORT}`);
});
