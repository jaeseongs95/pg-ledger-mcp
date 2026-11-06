import { readFileSync } from 'node:fs';

const target = /^mcp__.+_ledger_(save|transition|record_fix|record_case|link|correct|batch|review|review_correct|whoami)$/;
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const validId = value => typeof value === 'string' && value.length >= 1 && value.length <= 128 && !/[^A-Za-z0-9._-]/.test(value);
const reply = output => process.stdout.write(`${JSON.stringify({ hookSpecificOutput: { hookEventName: 'PreToolUse', ...output } })}\n`);
const deny = reason => reply({ permissionDecision: 'deny', permissionDecisionReason: reason });

let event;
try {
  event = JSON.parse(readFileSync(0, 'utf8'));
} catch {
  deny('Ledger hook input must be valid JSON.');
  process.exit(0);
}
if (!target.test(typeof event?.tool_name === 'string' ? event.tool_name : '')) process.exit(0);

const input = event.tool_input;
if (!object(input)) {
  deny('Ledger arguments must be an object.');
} else if (Object.hasOwn(input, '_hostAttestation')) {
  deny('_hostAttestation is reserved for the host hook; omit it from tool arguments.');
} else if (!validId(event.session_id)) {
  deny('A valid host session_id is required for ledger calls.');
} else {
  const updatedInput = { ...input, _hostAttestation: `codex:${event.session_id}` };
  delete updatedInput.author_id;
  reply({ permissionDecision: 'allow', updatedInput });
}
