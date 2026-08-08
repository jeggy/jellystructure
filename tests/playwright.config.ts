import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  timeout: 120_000,
  retries: 1,
  // The app under test is a single shared backend (one config.toml, one SQLite DB) -- every spec
  // file here mutates global server state (Settings saves, library scans). Running spec files in
  // parallel workers races them against each other on that shared state; confirmed live 2026-08-08
  // (the first time this suite ever actually executed against a working CI stack) as a real test
  // hanging to its full timeout, not a flake. One worker at a time keeps files from stepping on
  // each other; tests within a single file can still run concurrently unless the file itself opts
  // into serial (see bazarr-dashboard.spec.ts).
  workers: 1,
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: process.env.APP_URL ?? "http://localhost:9505",
    headless: true,
    screenshot: "only-on-failure",
    video: "retain-on-failure",
  },
  projects: [{ name: "chromium", use: { browserName: "chromium" } }],
});
