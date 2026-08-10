import { appendFileSync, mkdirSync } from "node:fs";
import { dirname } from "node:path";
import type { SDKRateLimitEvent } from "@anthropic-ai/claude-agent-sdk";

/**
 * Spec build-order step 2: capture a real rate_limit_event and pin the
 * parser to its actual shape. The event's real fields were confirmed by
 * reading the installed SDK's own sdk.d.ts (SDKRateLimitInfo, camelCase:
 * resetsAt/rateLimitType/utilization, not the snake_case the first research
 * pass guessed from a WebSearch synthesis) -- this logs every one verbatim
 * so real payloads accumulate from actual usage, not just the type shape.
 */
export function logRateLimitEvent(logPath: string, event: SDKRateLimitEvent): void {
  mkdirSync(dirname(logPath), { recursive: true });
  const line = JSON.stringify({ observedAt: new Date().toISOString(), event });
  appendFileSync(logPath, line + "\n");
}
