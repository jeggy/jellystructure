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
const DEFAULT_PERMISSION_TIMEOUT_MS = 30 * 60 * 1000;
const DEFAULT_PERMISSION_TIMEOUT_REASON = "No response within the timeout — auto-denied by Towo.";

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
/**
 * Every session ever started by this process, across every reconnect -- returned so index.ts can
 * close them all on SIGTERM/SIGINT (spec dev-review addendum #2's "known gap": killing the runner
 * used to orphan its child claude subprocesses, since nothing ever called Query.close() on them).
 */
export function connectToControlPlane(wsBase: string, tokenOrCredential: string, roots: string[]): { activeSessions: Map<string, ManagedSession> } {
  const quotaLogPath = join(homedir(), ".towo-runner", "rate-limit-events.jsonl");
  let currentToken = tokenOrCredential;

  // Moved out of open() so a reconnect doesn't lose track of sessions started on a previous
  // connection -- their processes keep running regardless of the WS's own lifecycle.
  const activeQueries = new Map<string, ManagedSession>();
  const pendingByCommand = new Map<string, ManagedSession>();
  const pendingPermissions = new Map<string, { resolve: (result: PermissionResult) => void; timer: ReturnType<typeof setTimeout> }>();
  const pendingLoads = new Map<string, (entries: SessionStoreEntry[] | null) => void>();

  // send() reads this dynamically rather than closing over one WebSocket instance, so a session
  // (and its SessionStore) started before a reconnect keeps relaying correctly afterward instead of
  // writing to a dead socket.
  let currentWs: WebSocket | null = null;
  function send(msg: RunnerToControl) {
    currentWs?.send(JSON.stringify(msg));
  }

  function open() {
    const url = `${wsBase}/api/towo/runner-link?token=${encodeURIComponent(currentToken)}`;
    console.log(`[towo-runner] connecting to ${wsBase} ...`);
    const ws = new WebSocket(url);
    currentWs = ws;

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
      permissionTimeoutMs: number | undefined,
      permissionTimeoutReason: string | undefined,
      onStarted: (session: ManagedSession) => void,
    ) {
      let sawSessionId: string | undefined = resumeSessionId;
      const timeoutMs = permissionTimeoutMs ?? DEFAULT_PERMISSION_TIMEOUT_MS;
      const timeoutReason = permissionTimeoutReason ?? DEFAULT_PERMISSION_TIMEOUT_REASON;
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
              // Spec §D -- "no built-in timeout" from the SDK; an ignored request would otherwise
              // block this session's tool call (and everything after it) forever.
              const timer = setTimeout(() => {
                if (!pendingPermissions.has(meta.requestId)) return;
                pendingPermissions.delete(meta.requestId);
                console.warn(`[towo-runner] permission request ${meta.requestId} timed out after ${timeoutMs}ms -- auto-denying`);
                resolve({ behavior: "deny", message: timeoutReason });
                send({ type: "permission_timeout", sessionId: sawSessionId ?? "", requestId: meta.requestId, reason: timeoutReason });
              }, timeoutMs);
              pendingPermissions.set(meta.requestId, { resolve, timer });
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
          runSession(msg.folderPath, msg.prompt, msg.maxTurns, undefined, msg.commandId, msg.permissionProfile, msg.permissionTimeoutMs, msg.permissionTimeoutReason, (session) => {
            pendingByCommand.set(msg.commandId, session);
          });
          break;
        }
        case "resume_session": {
          // Build-order step 6 -- TowoAutoContinueScheduler's command once a paused session's quota
          // window has reset. The SDK resumes by session id; sessionStore.load() supplies the history.
          // permissionProfile is the control plane's own record of what this session was started
          // with (towo_session.permission_profile) -- preserved across the resume, not re-guessed.
          console.log(`[towo-runner] resuming session ${msg.sessionId} after quota reset (profile=${msg.permissionProfile ?? "normal"})`);
          runSession(msg.folderPath, msg.prompt, undefined, msg.sessionId, undefined, msg.permissionProfile, msg.permissionTimeoutMs, msg.permissionTimeoutReason, () => {});
          break;
        }
        case "interrupt": {
          activeQueries.get(msg.sessionId)?.query.interrupt();
          break;
        }
        case "permission_decision": {
          const pending = pendingPermissions.get(msg.requestId);
          if (!pending) break;
          pendingPermissions.delete(msg.requestId);
          clearTimeout(pending.timer);
          // Same lesson as startManagedSession's options object: an explicitly-present key with an
          // undefined value (not simply omitted) previously made the SDK hang rather than proceed --
          // never include a key here unless there's a real value for it.
          pending.resolve(
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
      if (currentWs === ws) currentWs = null;
      console.log(`[towo-runner] disconnected (code ${event.code}) -- reconnecting in ${RECONNECT_DELAY_MS}ms`);
      setTimeout(open, RECONNECT_DELAY_MS);
    });

    ws.addEventListener("error", (event) => {
      console.error("[towo-runner] connection error:", (event as { message?: string }).message ?? event);
    });
  }

  open();
  return { activeSessions: activeQueries };
}
