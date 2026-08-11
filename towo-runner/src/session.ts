import { query, type CanUseTool, type PermissionMode, type PermissionResult, type Query, type SDKUserMessage, type SessionStore } from "@anthropic-ai/claude-agent-sdk";
import { logRateLimitEvent } from "./quotaLog.js";
import { AsyncQueue } from "./asyncQueue.js";

/**
 * Spec §D — named permission profiles, never a naked allow-list of dangerous tools. Read-only uses
 * `dontAsk` (canUseTool never fires per the SDK; anything off the allow-list is auto-denied, no
 * relay to the control plane at all). Unrestricted requires deliberate per-session opt-in in the UI
 * (composer), enforced there, not here -- this map just knows how to run whatever profile it's given.
 */
export const PERMISSION_PROFILES: Record<string, { permissionMode: PermissionMode; allowedTools?: string[] }> = {
  read_only: { permissionMode: "dontAsk", allowedTools: ["Read", "Glob", "Grep"] },
  normal: { permissionMode: "default" },
  autonomous: { permissionMode: "acceptEdits" },
  unrestricted: { permissionMode: "bypassPermissions" },
};

function resolveProfile(name: string | undefined) {
  return PERMISSION_PROFILES[name ?? "normal"] ?? PERMISSION_PROFILES.normal;
}

export type RunSessionOptions = {
  cwd: string;
  prompt: string;
  quotaLogPath: string;
};

/**
 * Spec build-order step 1: one hardcoded session, streaming to stdout.
 * canUseTool denies everything -- this proof-of-concept only needs to prove
 * the SDK/auth/streaming loop works end to end, and a deny-all callback is
 * both the safest possible first run and a real exercise of the exact
 * callback Towo's remote-approval UI (§D) will be built on. The fields
 * logged here (title/displayName/requestId) are the SDK's own ready-made
 * approval-prompt text -- confirmed by reading the installed .d.ts, not
 * assumed from docs.
 */
export async function runOneSession(opts: RunSessionOptions): Promise<void> {
  const denyAll: CanUseTool = async (toolName, input, options): Promise<PermissionResult> => {
    console.log(`\n[towo-runner] canUseTool fired -- ${options.title ?? toolName}`);
    console.log(`  displayName: ${options.displayName ?? "(none)"}`);
    console.log(`  requestId:   ${options.requestId}`);
    console.log(`  input:       ${JSON.stringify(input)}`);
    console.log(`  -> denying (proof-of-concept runner never auto-approves)\n`);
    return { behavior: "deny", message: "towo-runner v1 is a read-only proof of concept; nothing is approved yet." };
  };

  const q = query({
    prompt: opts.prompt,
    options: {
      cwd: opts.cwd,
      canUseTool: denyAll,
      permissionMode: "default",
    },
  });

  for await (const message of q) {
    switch (message.type) {
      case "assistant": {
        for (const block of message.message.content) {
          if (block.type === "text") {
            process.stdout.write(block.text);
          }
        }
        break;
      }
      case "rate_limit_event": {
        logRateLimitEvent(opts.quotaLogPath, message);
        console.log(`\n[towo-runner] rate_limit_event: status=${message.rate_limit_info.status} type=${message.rate_limit_info.rateLimitType ?? "?"} resetsAt=${message.rate_limit_info.resetsAt ?? "?"}`);
        break;
      }
      case "result": {
        console.log(`\n\n[towo-runner] session ${message.session_id} finished: ${message.subtype}`);
        break;
      }
      default:
        break;
    }
  }
}

export type ManagedSessionCallbacks = {
  /** Every SDKMessage, forwarded opaquely -- the control plane inspects only what it needs
   *  (spec build-order step 3's "session index"), same principle as the report's SessionStore design. */
  onMessage: (raw: unknown) => void;
  /** Mirrors the real canUseTool shape (title/displayName/requestId confirmed live against the
   *  pinned SDK -- see reference-claude-agent-sdk-facts memory). Held open in-process until the
   *  control plane relays a decision back down this same connection; the SDK's own out-of-band
   *  null-return mechanism is a possible future optimization, not needed for this to be correct. */
  onPermissionRequest: (
    toolName: string,
    input: Record<string, unknown>,
    meta: { requestId: string; title?: string; displayName?: string },
  ) => Promise<PermissionResult>;
};

export type StartManagedSessionOptions = {
  cwd: string;
  prompt: string;
  quotaLogPath: string;
  callbacks: ManagedSessionCallbacks;
  maxTurns?: number;
  /** Build-order step 5 -- when provided, every session (new or resumed) mirrors its transcript
   *  through here, making the control plane the durable copy (spec §4.3). */
  sessionStore?: SessionStore;
  /** Build-order step 6 -- set only for TowoAutoContinueScheduler's resume_session command. Resume
   *  conventionally pairs with a NEW prompt (a continuation), not a bare reconnect -- see the spec's
   *  open question on what an auto-continue actually resumes with; "Continue." is a placeholder
   *  until real usage data settles what this should say. */
  resumeSessionId?: string;
  /** One of PERMISSION_PROFILES' keys (spec §D); defaults to "normal" (relay everything). */
  permissionProfile?: string;
};

export type ManagedSession = {
  query: Query;
  /** Feeds a new turn into this session at any point in its life, including long after it went
   *  idle. Backed by an AsyncQueue the session's prompt IS, from the start -- see asyncQueue.ts's
   *  doc comment for why Query.streamInput() alone is not safe for this (confirmed live). */
  pushMessage: (text: string) => void;
};

function userMessage(text: string): SDKUserMessage {
  return { type: "user", message: { role: "user", content: text }, parent_tool_use_id: null };
}

/**
 * Build-order step 3+ — a session driven by the control plane rather than a hardcoded local prompt.
 * Always runs in streaming-input mode (the prompt is an AsyncQueue, not a plain string) so the
 * underlying process stays alive indefinitely rather than exiting once the first turn completes --
 * required for send_message to reach a session that's been sitting idle. Returns the live handle
 * immediately (before the message loop finishes) so the caller can index it by session id for
 * interrupt()/pushMessage() once the first message reveals that id.
 */
export function startManagedSession(opts: StartManagedSessionOptions): ManagedSession {
  const { cwd, prompt, quotaLogPath, callbacks, maxTurns, sessionStore, resumeSessionId, permissionProfile } = opts;
  const canUseTool: CanUseTool = async (toolName, input, options) =>
    callbacks.onPermissionRequest(toolName, input, {
      requestId: options.requestId,
      title: options.title,
      displayName: options.displayName,
    });
  const profile = resolveProfile(permissionProfile);

  const inputQueue = new AsyncQueue<SDKUserMessage>();
  inputQueue.push(userMessage(prompt));

  console.log(`[towo-runner] starting managed session: cwd=${cwd} resume=${resumeSessionId ?? "(new)"} maxTurns=${maxTurns ?? "(default)"} profile=${permissionProfile ?? "normal"}`);
  const q = query({
    prompt: inputQueue,
    options: {
      cwd, canUseTool, permissionMode: profile.permissionMode,
      ...(profile.allowedTools ? { allowedTools: profile.allowedTools } : {}),
      ...(maxTurns != null ? { maxTurns } : {}),
      ...(sessionStore ? { sessionStore } : {}),
      ...(resumeSessionId ? { resume: resumeSessionId } : {}),
    },
  });

  (async () => {
    try {
      for await (const message of q) {
        if (message.type === "rate_limit_event") {
          logRateLimitEvent(quotaLogPath, message);
        }
        callbacks.onMessage(message);
      }
    } catch (err) {
      console.error("[towo-runner] managed session iteration failed:", err);
    }
  })();

  return { query: q, pushMessage: (text: string) => inputQueue.push(userMessage(text)) };
}
