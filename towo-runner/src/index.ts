import { join } from "node:path";
import { homedir } from "node:os";
import { discoverFolders } from "./roots.js";
import { runOneSession } from "./session.js";

/**
 * towo-runner v1 -- spec build-order step 1. Parses repeatable --root flags,
 * discovers folders under them (git repos found at the root or one level
 * below -- spec §B: "folders are discovered, not declared"), and runs one
 * hardcoded session against the first discovered folder to prove the Agent
 * SDK + local Claude Code auth + message streaming work end to end.
 *
 * No control plane, no WebSocket transport, no persistence beyond the
 * quota-event log yet -- those are later build-order steps.
 */
function parseArgs(argv: string[]): { roots: string[]; prompt: string } {
  const roots: string[] = [];
  let prompt = "Reply with a single short sentence confirming you can see this folder. Do not use any tools.";
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === "--root") {
      const value = argv[++i];
      if (!value) throw new Error("--root requires a path argument");
      roots.push(value);
    } else if (argv[i] === "--prompt") {
      const value = argv[++i];
      if (!value) throw new Error("--prompt requires a value");
      prompt = value;
    }
  }
  return { roots, prompt };
}

async function main() {
  const { roots, prompt } = parseArgs(process.argv.slice(2));
  if (roots.length === 0) {
    console.error("usage: towo-runner --root <path> [--root <path> ...] [--prompt \"...\"]");
    process.exit(1);
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
