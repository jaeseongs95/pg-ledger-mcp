#!/usr/bin/env node
// Official PreToolUse contract: https://code.claude.com/docs/en/hooks#pretooluse
// session_id is the host conversation id. agent_id is intentionally not used.
import { readFileSync } from 'node:fs';

export function attest(event) {
  if (!/^mcp__.*ledger_(save|transition|record_fix|record_case|link|correct|batch|review|review_correct|whoami)$/.test(event.tool_name ?? '')) return {};
  const deny = (reason) => ({ hookSpecificOutput: { hookEventName: 'PreToolUse', permissionDecision: 'deny', permissionDecisionReason: reason } });
  const input = event.tool_input;
  if (!input || typeof input !== 'object' || Array.isArray(input)) return deny('ledger: tool_input must be an object');
  if (Object.hasOwn(input, '_hostAttestation')) return deny('ledger: _hostAttestation is reserved for the host hook; omit it');
  if (typeof event.session_id !== 'string' || !/^[A-Za-z0-9._-]{1,128}$/.test(event.session_id)) return deny('ledger: host session_id is missing or invalid');
  const { author_id: ignoredLegacyAuthor, ...rest } = input;
  // Omit permissionDecision to retain the host permission policy; do not auto-approve.
  return { hookSpecificOutput: { hookEventName: 'PreToolUse', updatedInput: { ...rest, _hostAttestation: `claude:${event.session_id}` } } };
}

try {
  process.stdout.write(`${JSON.stringify(attest(JSON.parse(readFileSync(0, 'utf8'))))}\n`);
} catch {
  console.error('ledger: invalid hook input');
  process.exitCode = 2; // PreToolUse exit 2 blocks the call.
}
