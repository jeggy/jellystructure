import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { homedir, hostname, platform } from "node:os";
import type { PermissionResult, Query } from "@anthropic-ai/claude-agent-sdk";
import { discoverFolders } from "./roots.js";
import { startManagedSession } from "./session.js";
import type { ControlToRunner, RunnerToControl } from "./protocol.js";

const SDK_VERSION = "0.3.227"; // pinned -- see towo-runner/README.md

const RECONNECT_DELAY_MS = 5_000;

function credentialPath(): string {
  return join(homedir(), ".towo-runner", "credential.json");
}

export function loadSavedCredential(): string | null {
  const path = credentialPath();
  if (!existsSync(path)) return null;
  try {
    return JSON.parse(readFileSync(path, "utf8")).credential ?? null;
  } catch {
    return null;
  }
}

function saveCredential(runnerId: string, credential: string): void {
  const path = credentialPath();
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, JSON.stringify({ runnerId, credential }, null, 2));
}

/**
 * Build-order step 3+ — dials the control plane's runner-link WS and keeps it open, reconnecting on
 * drop (spec §3: "the runner dials out and keeps a reconnecting WebSocket open"). [wsBase] is the
 * bare origin (e.g. "wss://host:port"); [tokenOrCredential] is either a freshly-minted enrollment
 * token (first connect) or a previously-saved runner credential (every connect after).
 */
export function connectToControlPlane(wsBase: string, tokenOrCredential: string, roots: string[]): void {
  const quotaLogPath = join(homedir(), ".towo-runner", "rate-limit-events.jsonl");
  let currentToken = tokenOrCredential;

  function open() {
    const url = `${wsBase}/api/towo/runner-link?token=${encodeURIComponent(currentToken)}`;
    console.log(`[towo-runner] connecting to ${wsBase} ...`);
    const ws = new WebSocket(url);

    // sessionId -> live Query handle, so interrupt()/future streamInput() can reach a running session.
    const activeQueries = new Map<string, Query>();
    // commandId -> Query handle, until the first message reveals the real Claude session_id.
    const pendingByCommand = new Map<string, Query>();
    const pendingPermissions = new Map<string, (result: PermissionResult) => void>();

    function send(msg: RunnerToControl) {
      ws.send(JSON.stringify(msg));
    }

    ws.addEventListener("open", () => {
      console.log("[towo-runner] connected");
      const folders = discoverFolders(roots);
      send({
        type: "hello",
        hostLabel: hostname(),
        authMode: "subscription",
        agentSdkVersion: SDK_VERSION,
        os: platform(),
        claudeAuthOk: true,
        allowedRoots: roots,
      });
      send({ type: "folders", folders: folders.map((f) => ({ name: f.path.split("/").pop() ?? f.path, absPath: f.path })) });
    });

    ws.addEventListener("message", (event) => {
      let msg: ControlToRunner;
      try {
        msg = JSON.parse(event.data.toString());
      } catch (err) {
        console.error("[towo-runner] malformed message from control plane:", err);
        return;
      }

      switch (msg.type) {
        case "enrolled": {
          currentToken = msg.credential;
          saveCredential(msg.runnerId, msg.credential);
          console.log(`[towo-runner] enrolled as runner ${msg.runnerId} -- credential saved to ${credentialPath()}`);
          break;
        }
        case "start_session": {
          let sawSessionId: string | undefined;
          const q = startManagedSession(
            msg.folderPath,
            msg.prompt,
            quotaLogPath,
            {
              onMessage: (raw) => {
                const sessionId = (raw as { session_id?: string }).session_id;
                if (sessionId && sawSessionId === undefined) {
                  sawSessionId = sessionId;
                  const pending = pendingByCommand.get(msg.commandId);
                  if (pending) {
                    activeQueries.set(sessionId, pending);
                    pendingByCommand.delete(msg.commandId);
                  }
                  send({ type: "session_started", commandId: msg.commandId, sessionId });
                }
                if (sawSessionId) send({ type: "session_message", sessionId: sawSessionId, message: raw });
              },
              onPermissionRequest: (toolName, input, meta) =>
                new Promise<PermissionResult>((resolve) => {
                  pendingPermissions.set(meta.requestId, resolve);
                  send({
                    type: "permission_request",
                    sessionId: sawSessionId ?? "",
                    requestId: meta.requestId,
                    toolName,
                    input,
                    title: meta.title,
                    displayName: meta.displayName,
                  });
                }),
            },
            msg.maxTurns,
          );
          pendingByCommand.set(msg.commandId, q);
          break;
        }
        case "interrupt": {
          activeQueries.get(msg.sessionId)?.interrupt();
          break;
        }
        case "permission_decision": {
          const resolve = pendingPermissions.get(msg.requestId);
          if (!resolve) break;
          pendingPermissions.delete(msg.requestId);
          resolve(
            msg.decision === "allow"
              ? { behavior: "allow", updatedInput: undefined as never }
              : { behavior: "deny", message: msg.reason ?? "Denied via Towo" },
          );
          break;
        }
        case "send_message": {
          // Multi-turn requires streaming input mode (an AsyncIterable prompt) from session start --
          // v1's startManagedSession takes a single string prompt. Deferred to build-order step 8
          // (the composer needs this for an idle session); logging rather than silently dropping it.
          console.warn(`[towo-runner] send_message for ${msg.sessionId} not yet supported in v1`);
          break;
        }
      }
    });

    ws.addEventListener("close", (event) => {
      console.log(`[towo-runner] disconnected (code ${event.code}) -- reconnecting in ${RECONNECT_DELAY_MS}ms`);
      setTimeout(open, RECONNECT_DELAY_MS);
    });

    ws.addEventListener("error", (event) => {
      console.error("[towo-runner] connection error:", (event as { message?: string }).message ?? event);
    });
  }

  open();
}
