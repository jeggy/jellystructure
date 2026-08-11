import { query, type CanUseTool, type PermissionResult, type Query, type SessionStore } from "@anthropic-ai/claude-agent-sdk";
import { logRateLimitEvent } from "./quotaLog.js";

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
};

/**
 * Build-order step 3+ — a session driven by the control plane rather than a hardcoded local prompt.
 * Returns the live Query handle immediately (before the message loop finishes) so the caller can
 * index it by session id for interrupt()/streamInput() once the first message reveals that id.
 */
export function startManagedSession(opts: StartManagedSessionOptions): Query {
  const { cwd, prompt, quotaLogPath, callbacks, maxTurns, sessionStore, resumeSessionId } = opts;
  const canUseTool: CanUseTool = async (toolName, input, options) =>
    callbacks.onPermissionRequest(toolName, input, {
      requestId: options.requestId,
      title: options.title,
      displayName: options.displayName,
    });

  console.log(`[towo-runner] starting managed session: cwd=${cwd} resume=${resumeSessionId ?? "(new)"} maxTurns=${maxTurns ?? "(default)"}`);
  const q = query({
    prompt,
    options: {
      cwd, canUseTool, permissionMode: "default",
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

  return q;
}
