/**
 * Mirrors src/linuxX64Main/kotlin/dev/jellystructure/towo/TowoProtocol.kt by hand -- that Kotlin file
 * is the source of truth (see its own doc comment). Keep both in sync manually; there is no codegen
 * bridging Kotlin and TypeScript here.
 */

export type FolderReport = { name: string; absPath: string };

export type RunnerToControl =
  | {
      type: "hello";
      hostLabel?: string;
      authMode?: string;
      agentSdkVersion?: string;
      os?: string;
      claudeAuthOk: boolean;
      allowedRoots: string[];
    }
  | { type: "folders"; folders: FolderReport[] }
  | { type: "session_started"; commandId: string; sessionId: string }
  | { type: "session_message"; sessionId: string; message: unknown }
  | {
      type: "permission_request";
      sessionId: string;
      requestId: string;
      toolName: string;
      input: unknown;
      title?: string;
      displayName?: string;
    }
  // Build-order step 5 -- the SDK's own SessionStore.append()/load() calls, relayed opaquely so the
  // control plane becomes the durable transcript mirror. NOT the same thing as session_message
  // (the live SDKMessage stream, for indexing + browser broadcast) -- these are the SDK's own
  // JSONL-line entries, for durability + resume.
  | { type: "transcript_append"; sessionId: string; subpath?: string; entries: unknown[] }
  | { type: "transcript_load_request"; requestId: string; sessionId: string; subpath?: string };

export type ControlToRunner =
  | { type: "enrolled"; runnerId: string; credential: string }
  | {
      type: "start_session";
      commandId: string;
      folderPath: string;
      prompt: string;
      maxTurns?: number;
      permissionProfile: string;
    }
  | { type: "send_message"; sessionId: string; text: string }
  | { type: "interrupt"; sessionId: string }
  | { type: "permission_decision"; requestId: string; decision: "allow" | "deny"; reason?: string }
  | { type: "transcript_load_response"; requestId: string; entries: unknown[] | null }
  // Build-order step 6 -- the control plane owns the paused_quota/continue_after_reset schedule
  // (it already persists that state and already runs a periodic scheduler), so it tells the runner
  // when to resume rather than the runner re-deriving arming state itself.
  | { type: "resume_session"; sessionId: string; folderPath: string; prompt: string };
