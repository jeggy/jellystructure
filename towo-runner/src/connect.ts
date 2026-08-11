import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { homedir, hostname, platform } from "node:os";
import { randomUUID } from "node:crypto";
import type { PermissionResult, SessionStore, SessionStoreEntry } from "@anthropic-ai/claude-agent-sdk";
import { discoverFolders } from "./roots.js";
import { startManagedSession, type ManagedSession } from "./session.js";
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

    // sessionId -> live session handle, so interrupt()/pushMessage() can reach a running session --
    // reliably, at any later time, because the session's own prompt is a live queue (asyncQueue.ts),
    // not a one-shot string (confirmed live: Query.streamInput() alone is not safe for this).
    const activeQueries = new Map<string, ManagedSession>();
    // commandId -> session handle, until the first message reveals the real Claude session_id.
    const pendingByCommand = new Map<string, ManagedSession>();
    const pendingPermissions = new Map<string, (result: PermissionResult) => void>();
    // Build-order step 5 -- requestId -> resolver for a load() call awaiting the control plane's reply.
    const pendingLoads = new Map<string, (entries: SessionStoreEntry[] | null) => void>();

    function send(msg: RunnerToControl) {
      ws.send(JSON.stringify(msg));
    }

    // Build-order step 5 -- the SDK's SessionStore, implemented by relaying append()/load() over this
    // same connection so the control plane becomes the durable transcript mirror (spec §4.3). One
    // instance per connection since it closes over `send`/`pendingLoads`, which are connection-scoped.
    const sessionStore: SessionStore = {
      async append(key, entries) {
        send({ type: "transcript_append", sessionId: key.sessionId, subpath: key.subpath, entries });
      },
      load(key) {
        return new Promise<SessionStoreEntry[] | null>((resolve) => {
          const requestId = randomUUID();
          pendingLoads.set(requestId, resolve);
          send({ type: "transcript_load_request", requestId, sessionId: key.sessionId, subpath: key.subpath });
        });
      },
    };

    function runSession(
      folderPath: string,
      prompt: string,
      maxTurns: number | undefined,
      resumeSessionId: string | undefined,
      commandId: string | undefined,
      permissionProfile: string | undefined,
      onStarted: (session: ManagedSession) => void,
    ) {
      let sawSessionId: string | undefined = resumeSessionId;
      const session = startManagedSession({
        cwd: folderPath,
        prompt,
        quotaLogPath,
        maxTurns,
        sessionStore,
        resumeSessionId,
        permissionProfile,
        callbacks: {
          onMessage: (raw) => {
            const sessionId = (raw as { session_id?: string }).session_id;
            if (sessionId && sawSessionId === undefined) {
              sawSessionId = sessionId;
              activeQueries.set(sessionId, session);
              // Only a genuinely new session (start_session, carrying a commandId) needs to tell the
              // control plane its real Claude session id -- a resume already has a towo_session row.
              if (commandId) send({ type: "session_started", commandId, sessionId });
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
      });
      if (resumeSessionId) activeQueries.set(resumeSessionId, session);
      onStarted(session);
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
      console.log(`[towo-runner] <- ${msg.type}`);

      switch (msg.type) {
        case "enrolled": {
          currentToken = msg.credential;
          saveCredential(msg.runnerId, msg.credential);
          console.log(`[towo-runner] enrolled as runner ${msg.runnerId} -- credential saved to ${credentialPath()}`);
          break;
        }
        case "start_session": {
          runSession(msg.folderPath, msg.prompt, msg.maxTurns, undefined, msg.commandId, msg.permissionProfile, (session) => {
            pendingByCommand.set(msg.commandId, session);
          });
          break;
        }
        case "resume_session": {
          // Build-order step 6 -- TowoAutoContinueScheduler's command once a paused session's quota
          // window has reset. The SDK resumes by session id; sessionStore.load() supplies the history.
          console.log(`[towo-runner] resuming session ${msg.sessionId} after quota reset`);
          // Profile isn't persisted/relayed across auto-continue yet -- resumes fall back to "normal".
          // Known simplification, not a regression: the pre-resume flow had no profile concept at all.
          runSession(msg.folderPath, msg.prompt, undefined, msg.sessionId, undefined, undefined, () => {});
          break;
        }
        case "interrupt": {
          activeQueries.get(msg.sessionId)?.query.interrupt();
          break;
        }
        case "permission_decision": {
          const resolve = pendingPermissions.get(msg.requestId);
          if (!resolve) break;
          pendingPermissions.delete(msg.requestId);
          // Same lesson as startManagedSession's options object: an explicitly-present key with an
          // undefined value (not simply omitted) previously made the SDK hang rather than proceed --
          // never include a key here unless there's a real value for it.
          resolve(
            msg.decision === "allow"
              ? { behavior: "allow" }
              : { behavior: "deny", message: msg.reason ?? "Denied via Towo" },
          );
          break;
        }
        case "transcript_load_response": {
          const resolve = pendingLoads.get(msg.requestId);
          if (!resolve) break;
          pendingLoads.delete(msg.requestId);
          resolve((msg.entries as SessionStoreEntry[] | null) ?? null);
          break;
        }
        case "send_message": {
          // pushMessage() feeds the session's own live input queue (asyncQueue.ts) -- reliable at
          // any later time, unlike a bolted-on Query.streamInput() call (confirmed live: that either
          // throws "ProcessTransport is not ready for writing" or silently produces no response once
          // called from a genuinely separate later context, e.g. this WS handler).
          const session = activeQueries.get(msg.sessionId);
          if (!session) {
            console.warn(`[towo-runner] send_message for unknown/inactive session ${msg.sessionId}`);
            break;
          }
          session.pushMessage(msg.text);
          break;
        }
        default:
          console.warn(`[towo-runner] unrecognized message type from control plane:`, msg);
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
