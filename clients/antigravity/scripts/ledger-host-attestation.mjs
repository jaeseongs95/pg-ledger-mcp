import { readFileSync } from 'node:fs';

const ledgerTool = /(?:^|[:/_])ledger_(save|transition|record_fix|record_case|link|correct|batch|review|review_correct|whoami)$/;
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
const name = event?.toolCall?.name;
const envelope = event?.toolCall?.args;
const wrapped = name === 'call_mcp_tool';
if (wrapped) {
  if (!object(envelope) || typeof envelope.ServerName !== 'string' || envelope.ServerName.length === 0 || typeof envelope.ToolName !== 'string' || !ledgerTool.test(envelope.ToolName)) process.exit(0);
} else if (typeof name !== 'string' || !name.startsWith('mcp:') || !ledgerTool.test(name)) process.exit(0);

let input = wrapped ? envelope.Arguments : envelope;
const stringArguments = wrapped && typeof input === 'string';
if (stringArguments) {
  try {
    input = JSON.parse(input);
  } catch {
    deny('Ledger Arguments must contain a JSON object.');
    process.exit(0);
  }
}
if (!object(input)) {
  deny('Ledger arguments must be an object.');
} else if (Object.hasOwn(input, '_hostAttestation') || (wrapped && Object.hasOwn(envelope, '_hostAttestation'))) {
  deny('_hostAttestation is reserved for the host hook; omit it from tool arguments.');
} else if (!validId(event.conversationId)) {
  deny('A valid host conversationId is required for ledger calls.');
} else {
  // overwrite merges into the host tool args; replace only the nested MCP Arguments.
  const patch = { _hostAttestation: `gemini:${event.conversationId}` };
  const updated = { ...input, ...patch };
  const overwrite = wrapped ? { Arguments: stringArguments ? JSON.stringify(updated) : updated } : patch;
  reply({ decision: 'allow', overwrite });
}
