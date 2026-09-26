import { test, expect, Page } from "@playwright/test";
import { execSync } from "child_process";
import * as fs from "fs";
import * as path from "path";

/**
 * Phase 263 — "Replace from clean copy", end to end, on a real damaged file.
 *
 * The fixture (scripts/build-fixtures.sh, fixture 5) is a library file whose audio is reordered and
 * relabelled against its clean seeding copy — Danish first and default in the library, English first in
 * the release, the way this household's library really is — and damaged mid-file. tests/mock-qbittorrent
 * reports the seeding copy's folder as a torrent. Phase 254 carried the library's labels over by stream
 * position, so it would have written the release's English track under the Danish label and passed all
 * three of its own checks; this suite asserts, by per-stream hashes of the real files, that each label
 * ends up over its own audio.
 *
 * Named to run after scan-fixture.spec.ts (workers: 1, filename order), whose scans put the title in
 * the library; it still triggers a scan itself if the title is not there yet.
 *
 * ⚠ THIS SUITE MUTATES ITS OWN FIXTURE (the library file is replaced, the damaged original moved to
 * .js-quarantine), so locally it needs fresh fixtures before every run, like scan-fixture.spec.ts:
 *     rm -rf /tmp/jellystructure-fixtures && ./scripts/build-fixtures.sh
 */

const JF_USER = process.env.JELLYFIN_USER ?? "admin";
const JF_PASS = process.env.JELLYFIN_PASS ?? "password";
const FIXTURE_DIR = process.env.FIXTURE_DIR ?? "/tmp/jellystructure-fixtures";

const ID = "twotongues-id";
const NAME = "Two Tongues (2021).mkv";
// As the app sees them (FIXTURE_DIR is mounted at /media there)…
const APP_LIB = `/media/movies/Two Tongues (2021)/${NAME}`;
const APP_SRC = `/media/seeding/Two.Tongues.2021.1080p/${NAME}`;
// …and as this container sees them (the same directory, read-only, at FIXTURE_DIR).
const LIB = path.join(FIXTURE_DIR, "movies", "Two Tongues (2021)", NAME);
const SRC = path.join(FIXTURE_DIR, "seeding", "Two.Tongues.2021.1080p", NAME);
const QUARANTINED = path.join(FIXTURE_DIR, ".js-quarantine", "movies", "Two Tongues (2021)", NAME);

function sh(cmd: string): string {
  return execSync(cmd, { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
}

/** One stream's packet hash — what a stream copy leaves unchanged whatever the stream is called. */
function streamHash(file: string, index: number): string {
  return sh(`ffmpeg -nostdin -v error -i "${file}" -map 0:${index} -c copy -f streamhash -hash md5 - 2>/dev/null`).split("=")[1].trim();
}

function streams(file: string): Array<{ index: number; codec_type: string; tags?: { language?: string }; disposition?: { default?: number } }> {
  return JSON.parse(sh(`ffprobe -v quiet -print_format json -show_streams "${file}"`)).streams;
}

async function poll<T>(what: string, fn: () => Promise<T | null | undefined>, timeoutMs: number): Promise<T> {
  const until = Date.now() + timeoutMs;
  for (;;) {
    const v = await fn();
    if (v) return v;
    if (Date.now() > until) throw new Error(`timed out waiting for ${what}`);
    await new Promise((r) => setTimeout(r, 1000));
  }
}

test.describe.configure({ mode: "serial" });

test.describe("Phase 263 — a replacement puts each track's own sound under its own label", () => {
  let page: Page;

  const integrity = async () => {
    const r = await page.request.get(`/api/media/${ID}/health/integrity`);
    return r.status() === 200 ? await r.json() : null;
  };

  test.beforeAll(async ({ browser }) => {
    page = await browser.newPage();
    const login = await page.request.post("/api/auth/login", { data: { username: JF_USER, password: JF_PASS } });
    expect(login.ok()).toBeTruthy();

    // The title comes from a scan. scan-fixture.spec.ts has normally scanned already; if not, start one
    // (a 409 means one is running, which is as good).
    const present = async () => (await integrity()) ?? null;
    if (!(await poll("title", async () => present(), 10_000).catch(() => null))) {
      await page.request.post("/api/scan?full=true");
      await poll("the scan to add the title", present, 180_000);
    }
  });

  test.afterAll(async () => page?.close());

  test("the fixture is what the test assumes: reordered against the source, damaged", () => {
    expect(streams(LIB).map((s) => s.tags?.language ?? "und")).toEqual(["und", "dan", "swe", "eng", "eng"]);
    expect(streams(SRC).map((s) => s.tags?.language ?? "und")).toEqual(["und", "eng", "dan", "swe", "eng"]);
    expect(sh(`ffmpeg -nostdin -v error -i "${LIB}" -map 0 -c copy -f null - 2>&1 || true`)).toContain("matroska,webm");
  });

  test("a deep check finds the damage and names the clean copy qBittorrent is seeding", async () => {
    const queued = await page.request.post(`/api/media/${ID}/health/integrity/check?check=verify`);
    expect(queued.status()).toBeLessThan(300);
    const file = await poll("the deep check", async () => {
      const s = await integrity();
      return s?.files?.find((f: { path: string; state: string }) => f.path === APP_LIB && f.state === "damaged");
    }, 120_000);
    expect(file.sourcePath).toBe(APP_SRC);
    // FR-263-6: a replacement's command is asked for per file, never built while the page loads.
    expect(file.command ?? null).toBeNull();
  });

  test("the plan pairs every track by its content, out of order, and maps the source in the library's order", async () => {
    const r = await page.request.get(`/api/media/${ID}/health/integrity/plan?path=${encodeURIComponent(APP_LIB)}`);
    expect(r.status()).toBe(200);
    const plan = await r.json();
    expect(plan.refusal ?? null).toBeNull();
    expect(plan.sourcePath).toBe(APP_SRC);
    expect(plan.pairs.map((p: { libraryIndex: number; sourceIndex: number; language: string }) => [p.libraryIndex, p.sourceIndex, p.language])).toEqual([
      [0, 0, "und"], [1, 2, "dan"], [2, 3, "swe"], [3, 1, "eng"], [4, 4, "eng"],
    ]);
    expect(plan.pairs.every((p: { by: string }) => p.by === "content")).toBeTruthy();
    // The copy (the snippet's first line) maps the source track by track; `-map 0` appears only later,
    // where the checks read the new file whole.
    const copy = plan.command.split(" \\\n")[0];
    expect(copy).toContain("-i '/media/seeding/Two.Tongues.2021.1080p/Two Tongues (2021).mkv' -map 0:0 -map 0:2 -map 0:3 -map 0:1 -map 0:4 -c copy");
    expect(copy).not.toMatch(/-map 0 /);
    expect(plan.command).toContain("-f streamhash");

    // A path that is not this title's is not answered — the route reads files.
    const other = await page.request.get(`/api/media/${ID}/health/integrity/plan?path=${encodeURIComponent("/etc/passwd")}`);
    expect(other.status()).toBe(404);
  });

  test("the title page shows the pairing when the file's row is opened", async () => {
    await page.goto(`/#/media/${ID}`);
    const banner = page.locator("#integrity-banner");
    await expect(banner).toBeVisible({ timeout: 20_000 });
    await expect(banner).toContainText("Replace from clean copy (1)");
    await page.locator("#integrity-row-0 summary").click();
    const slot = page.locator("#integrity-plan-0");
    await expect(slot).toContainText("Your tracks ← the clean copy's", { timeout: 15_000 });
    await expect(slot).toContainText("1 dan ← 2");
    await expect(slot).toContainText("3 eng ← 1");
    await expect(slot.locator("button", { hasText: "Copy" })).toBeVisible();
  });

  test("Replace from clean copy puts the source's Danish audio under the Danish label", async () => {
    const r = await page.request.post("/api/media/health/integrity/repair", { data: { mediaId: ID, paths: [APP_LIB], lossy: false } });
    expect(r.status()).toBe(202);
    const { jobId } = await r.json();
    const job = await poll("the replace job", async () => {
      const jobs = await (await page.request.get("/api/jobs")).json();
      const all = [...(jobs.running ?? []), ...(jobs.queued ?? []), ...(jobs.recent ?? [])];
      const j = all.find((x: { id: string }) => x.id === jobId);
      return j && ["done", "failed", "cancelled"].includes(j.state) ? j : null;
    }, 180_000);
    expect(job.error ?? null, `the job refused: ${job.error}`).toBeNull();
    expect(job.state).toBe("done");

    // Labels: the library's, in the library's order, Danish still the default.
    const after = streams(LIB);
    expect(after.map((s) => s.tags?.language ?? "und")).toEqual(["und", "dan", "swe", "eng", "eng"]);
    expect(after.filter((s) => s.disposition?.default === 1 && s.codec_type === "audio").map((s) => s.tags?.language)).toEqual(["dan"]);

    // Content: every position carries the seeding copy's track of the same language, byte for byte.
    const expectedSource = [0, 2, 3, 1, 4];
    expectedSource.forEach((src, pos) => expect(streamHash(LIB, pos), `library track ${pos}`).toBe(streamHash(SRC, src)));
    // …which is exactly what 254's by-position copy got wrong: position 1 is not the source's position 1.
    expect(streamHash(LIB, 1)).not.toBe(streamHash(SRC, 1));

    // Clean now, the damaged original kept, and the page says so.
    expect(sh(`ffmpeg -nostdin -v error -i "${LIB}" -map 0 -c copy -f null - 2>&1 || true`)).not.toContain("matroska,webm");
    expect(fs.existsSync(QUARANTINED)).toBeTruthy();
    const s = await integrity();
    expect(s.files.find((f: { path: string }) => f.path === APP_LIB)).toBeUndefined();
  });
});
