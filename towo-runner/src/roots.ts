import { readdirSync, statSync, existsSync } from "node:fs";
import { join, resolve } from "node:path";

export type DiscoveredFolder = {
  /** Absolute, resolved path. */
  path: string;
  /** The --root this folder was discovered under. */
  root: string;
  /** True if `path` is itself a git repo (has a .git entry). */
  isRepo: boolean;
};

/**
 * Folders are discovered, not declared (Towo spec §B) -- a runner is handed
 * one or more --root paths and reports what it finds beneath them. A folder
 * is any git repo found at the root itself or one level below it. Depth is
 * capped at 1 deliberately: this mirrors how the user's own machines are
 * laid out (~/IdeaProjects/<repo>, ~), not a general-purpose repo crawler.
 */
export function discoverFolders(roots: string[]): DiscoveredFolder[] {
  const found: DiscoveredFolder[] = [];
  for (const rawRoot of roots) {
    const root = resolve(rawRoot);
    if (!existsSync(root)) {
      console.error(`[towo-runner] --root ${root} does not exist, skipping`);
      continue;
    }
    if (isGitRepo(root)) {
      found.push({ path: root, root, isRepo: true });
      continue; // a repo root doesn't get its own subdirs scanned as separate folders
    }
    let entries: string[];
    try {
      entries = readdirSync(root);
    } catch (err) {
      console.error(`[towo-runner] cannot read --root ${root}: ${(err as Error).message}`);
      continue;
    }
    for (const entry of entries) {
      if (entry.startsWith(".")) continue;
      const candidate = join(root, entry);
      let isDir = false;
      try {
        isDir = statSync(candidate).isDirectory();
      } catch {
        continue;
      }
      if (isDir && isGitRepo(candidate)) {
        found.push({ path: candidate, root, isRepo: true });
      }
    }
  }
  return found;
}

function isGitRepo(dir: string): boolean {
  return existsSync(join(dir, ".git"));
}

/** Validates a folder path is actually under one of the runner's allowed
 *  roots. The control plane only ever names a folder by path; this is the
 *  runner-side check that keeps a compromised control plane from pointing a
 *  session at an arbitrary path (spec §7). */
export function isUnderAllowedRoot(folderPath: string, roots: string[]): boolean {
  const resolved = resolve(folderPath);
  return roots.some((root) => {
    const resolvedRoot = resolve(root);
    return resolved === resolvedRoot || resolved.startsWith(resolvedRoot + "/");
  });
}
