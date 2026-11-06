import { readFileSync } from 'node:fs';

const target = /^mcp:(?:.*[:/_])?ledger_(save|transition|record_fix|record_case|link|correct|batch|review|review_correct|whoami)$/;
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const validId = value => typeof value === 'string' && value.length >= 1 && value.length <= 128 && !/[^A-Za-z0-9._-]/.test(value);
const reply = output => process.stdout.write(`${JSON.stringify(output)}\n`);
const deny = reason => reply({ decision: 'deny', reason });

let event;
try {
  event = JSON.parse(readFileSync(0, 'utf8'));
} catch {
  deny('Ledger hook input must be valid JSON.');
  process.exit(0);
}
if (!target.test(typeof event?.toolCall?.name === 'string' ? event.toolCall.name : '')) process.exit(0);

const input = event.toolCall.args;
if (!object(input)) {
  deny('Ledger arguments must be an object.');
} else if (Object.hasOwn(input, '_hostAttestation')) {
  deny('_hostAttestation is reserved for the host hook; omit it from tool arguments.');
} else if (!validId(event.conversationId)) {
  deny('A valid host conversationId is required for ledger calls.');
} else {
  // Antigravity merges overwrite into args; legacy author_id is ignored by the server.
  reply({ decision: 'allow', overwrite: { _hostAttestation: `gemini:${event.conversationId}` } });
}
