#!/usr/bin/env node
import { join } from "node:path";
import { homedir } from "node:os";
import { discoverFolders } from "./roots.js";
import { runOneSession } from "./session.js";
import { connectToControlPlane, loadSavedCredential } from "./connect.js";

/**
 * towo-runner -- the per-host runner daemon for Towo (Phase 162). Two modes:
 *
 *   --root <path> [--root <path> ...] [--prompt "..."]
 *     Build-order step 1's proof-of-concept: one hardcoded local session, streaming to stdout.
 *     No control plane involved. See session.ts's runOneSession.
 *
 *   --connect <wsBase> [--enroll-token <token>] --root <path> [--root <path> ...]
 *     Build-order step 3+: dials the control plane's runner-link WS and stays connected,
 *     running whatever sessions it's told to start. --enroll-token is required only on first
 *     connect (spec §B) -- every reconnect after that uses the credential saved locally.
 */
function parseArgs(argv: string[]): { roots: string[]; prompt: string; connect?: string; enrollToken?: string } {
  const roots: string[] = [];
  let prompt = "Reply with a single short sentence confirming you can see this folder. Do not use any tools.";
  let connect: string | undefined;
  let enrollToken: string | undefined;
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === "--root") {
      const value = argv[++i];
      if (!value) throw new Error("--root requires a path argument");
      roots.push(value);
    } else if (argv[i] === "--prompt") {
      const value = argv[++i];
      if (!value) throw new Error("--prompt requires a value");
      prompt = value;
    } else if (argv[i] === "--connect") {
      const value = argv[++i];
      if (!value) throw new Error("--connect requires a WebSocket base URL, e.g. wss://host:9505");
      connect = value.replace(/\/$/, "");
    } else if (argv[i] === "--enroll-token") {
      const value = argv[++i];
      if (!value) throw new Error("--enroll-token requires a value");
      enrollToken = value;
    }
  }
  return { roots, prompt, connect, enrollToken };
}

async function main() {
  const { roots, prompt, connect, enrollToken } = parseArgs(process.argv.slice(2));
  if (roots.length === 0) {
    console.error(
      "usage:\n" +
        '  towo-runner --root <path> [--root <path> ...] [--prompt "..."]\n' +
        "  towo-runner --connect <wsBase> [--enroll-token <token>] --root <path> [--root <path> ...]",
    );
    process.exit(1);
  }

  if (connect) {
    const token = enrollToken ?? loadSavedCredential();
    if (!token) {
      console.error("[towo-runner] no --enroll-token given and no saved credential found -- pass the token from the enrollment command shown in Towo's UI.");
      process.exit(1);
    }
    const { activeSessions } = connectToControlPlane(connect, token, roots);
    // Killing this process used to orphan its child claude subprocesses -- confirmed live via `ps aux`
    // after a test run, nothing ever called Query.close() on them. Graceful shutdown closes every
    // session's live process before this one exits.
    const shutdown = (signal: string) => {
      console.log(`[towo-runner] ${signal} received -- closing ${activeSessions.size} active session(s)`);
      for (const session of activeSessions.values()) {
        try {
          session.query.close();
        } catch (err) {
          console.error("[towo-runner] error closing a session during shutdown:", err);
        }
      }
      process.exit(0);
    };
    process.on("SIGTERM", () => shutdown("SIGTERM"));
    process.on("SIGINT", () => shutdown("SIGINT"));
    return; // connectToControlPlane keeps the process alive via its WS event listeners + reconnect loop
  }

  const folders = discoverFolders(roots);
  console.log(`[towo-runner] ${roots.length} root(s) -> ${folders.length} folder(s) discovered:`);
  for (const f of folders) {
    console.log(`  ${f.path}  (under root ${f.root})`);
  }

  const targetFolder = folders[0]?.path ?? roots[0];
  console.log(`\n[towo-runner] running one proof-of-concept session in: ${targetFolder}\n`);

  const quotaLogPath = join(homedir(), ".towo-runner", "rate-limit-events.jsonl");
  await runOneSession({ cwd: targetFolder, prompt, quotaLogPath });
}

main().catch((err) => {
  console.error("[towo-runner] fatal:", err);
  process.exit(1);
});
