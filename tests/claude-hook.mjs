// Run on TEST only: node --test tests/claude-hook.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const script = fileURLToPath(new URL('../clients/claude/hooks/host-attestation.mjs', import.meta.url));
const event = (name, input = {}) => ({ session_id: 'parent-123', agent_id: 'child-ignored', tool_name: `mcp__ledger__ledger_${name}`, tool_input: input });
function run(input) {
  const r = spawnSync(process.execPath, [script], { input: typeof input === 'string' ? input : JSON.stringify(input), encoding: 'utf8' });
  return { status: r.status, stderr: r.stderr, output: r.stdout ? JSON.parse(r.stdout) : null };
}
for (const name of ['save','transition','record_fix','record_case','link','correct','batch','review','review_correct','whoami']) {
  test(`${name}: preserve fields, use host parent id, remove legacy author without auto approval`, () => {
    const r = run(event(name, { author_id: 'model:forged', request_id: 'kept', fields: '{"title":"kept"}' }));
    assert.equal(r.status,0);
    assert.deepEqual(r.output,{ hookSpecificOutput: { hookEventName: 'PreToolUse', updatedInput: { request_id: 'kept', fields: '{"title":"kept"}', _hostAttestation: 'claude:parent-123' } } });
  });
}
test('reserved field is denied even for null or the expected value', () => {
  for (const value of [null,'claude:parent-123','forged']) {
    assert.equal(run(event('whoami',{ _hostAttestation: value })).output.hookSpecificOutput.permissionDecision,'deny');
  }
});
test('missing or malformed host id is denied without looking at agent id', () => {
  for (const session_id of [undefined,'','bad:id','x'.repeat(129)]) {
    assert.equal(run({ ...event('save'),session_id }).output.hookSpecificOutput.permissionDecision,'deny');
  }
});
test('other tools are unchanged', () => assert.deepEqual(run({ ...event('save'),tool_name: 'mcp__other__read' }).output,{}));
test('malformed JSON blocks via exit 2', () => assert.equal(run('{').status,2));
