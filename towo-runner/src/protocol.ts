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
    };

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
  | { type: "permission_decision"; requestId: string; decision: "allow" | "deny"; reason?: string };
