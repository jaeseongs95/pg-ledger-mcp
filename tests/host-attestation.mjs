import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const root = resolve(process.argv[2]);
const tools = ['save', 'transition', 'record_fix', 'record_case', 'link', 'correct', 'batch', 'review', 'review_correct', 'whoami'];
const id = 'parent-1234_ab.cd';
const adapters = [
  { host: 'codex', vendor: 'codex', event: (name, args, sid = id) => ({ tool_name: name, tool_input: args, session_id: sid, agent_id: 'child-9999' }), name: op => `mcp__test__ledger_${op}`, decision: out => out.hookSpecificOutput?.permissionDecision, args: (input, out) => out.hookSpecificOutput.updatedInput },
  { host: 'antigravity', vendor: 'gemini', event: (name, args, sid = id) => ({ toolCall: { name, args }, conversationId: sid, agent_id: 'child-9999' }), name: op => `mcp:test:ledger_${op}`, decision: out => out.decision, args: (input, out) => ({ ...input, ...out.overwrite }) },
];
function invoke(adapter, event) {
  const script = process.env[`HOOK_${adapter.host.toUpperCase()}`] || resolve(root, `clients/${adapter.host}/scripts/ledger-host-attestation.mjs`);
  const result = spawnSync(process.execPath, [script], { input: typeof event === 'string' ? event : JSON.stringify(event), encoding: 'utf8', timeout: 5000 });
  assert.equal(result.error, undefined);
  assert.equal(result.status, 0);
  assert.equal(result.stderr, '');
  return result.stdout === '' ? null : JSON.parse(result.stdout);
}
for (const a of adapters) {
  test(`${a.host} S01: all ten tools use host metadata and preserve other fields`, () => {
    for (const tool of tools) {
      const input = JSON.parse('{"project":"TEST","nested":{"items":[1,null,false]},"note":"한글","__proto__":{"sentinel":true},"author_id":"other:declared"}');
      const out = invoke(a, a.event(a.name(tool), input));
      assert.equal(a.decision(out), 'allow');
      const actual = a.args(input, out);
      const expected = { ...input, _hostAttestation: `${a.vendor}:${id}` };
      if (a.host === 'codex') delete expected.author_id;
      assert.deepEqual(actual, expected);
      assert.equal(out.permissionOverrides, undefined);
    }
  });
  test(`${a.host} S02: own reserved key always denies`, () => {
    for (const value of [null, '', false, 0, {}, [], `${a.vendor}:${id}`, 'other:forged']) {
      for (const tool of tools) {
        const out = invoke(a, a.event(a.name(tool), { _hostAttestation: value }));
        assert.equal(a.decision(out), 'deny');
        assert.match(JSON.stringify(out), /reserved/);
        assert.equal(out.overwrite, undefined);
        assert.equal(out.hookSpecificOutput?.updatedInput, undefined);
      }
    }
  });
  test(`${a.host} S03: invalid or missing host ID denies without fallback`, () => {
    for (const sid of [null, '', 3, {}, 'x'.repeat(129), 'white space', 'other:id', 'x\n', '한글']) {
      assert.equal(a.decision(invoke(a, a.event(a.name('save'), { author_id: 'codex:fallback' }, sid))), 'deny');
    }
    const event = a.event(a.name('whoami'), {});
    delete event.session_id; delete event.conversationId;
    assert.equal(a.decision(invoke(a, event)), 'deny');
  });
  test(`${a.host} S04: existing database ID boundaries remain valid`, () => {
    for (const sid of ['x', 'a._-Z9', 'x'.repeat(128)]) {
      const input = {};
      const out = invoke(a, a.event(a.name('whoami'), input, sid));
      assert.equal(a.args(input, out)._hostAttestation, `${a.vendor}:${sid}`);
    }
  });
  test(`${a.host} S05: queries and unrelated names pass unchanged`, () => {
    for (const name of [a.name('get'), a.name('list'), a.name('history'), a.name('save_extra'), 'run_command', 'ledger_save', 'mcp__test__other_save']) {
      assert.equal(invoke(a, a.event(name, { _hostAttestation: 'model:value' }, null)), null);
    }
  });
  test(`${a.host} S06: malformed arguments and JSON deny explicitly`, () => {
    for (const args of [null, [], 'text', 7]) assert.equal(a.decision(invoke(a, a.event(a.name('save'), args))), 'deny');
    assert.equal(a.decision(invoke(a, '{')), 'deny');
    assert.equal(invoke(a, null), null);
  });
  test(`${a.host} S07: each invocation uses the supplied host ID, no child state`, () => {
    for (const sid of ['parent-A', 'parent-B', 'parent-A']) {
      const out = invoke(a, a.event(a.name('save'), {}, sid));
      assert.equal(a.args({}, out)._hostAttestation, `${a.vendor}:${sid}`);
    }
  });
}
test('S08: native templates point to existing scripts and preserve permission policy', () => {
  const c = JSON.parse(readFileSync(resolve(root, 'clients/codex/hooks/hooks.json')));
  const ag = JSON.parse(readFileSync(resolve(root, 'clients/antigravity/hooks.json')));
  const cg = c.hooks.PreToolUse[0];
  const gg = ag['pg-ledger-host-attestation'].PreToolUse[0];
  for (const op of tools) assert.ok(new RegExp(cg.matcher).test(`mcp__test__ledger_${op}`));
  assert.equal(new RegExp(cg.matcher).test('mcp__test__ledger_get'), false);
  assert.ok(new RegExp(gg.matcher).test('mcp:test:ledger_save'));
  assert.equal(gg.hooks[0].command, 'node ./scripts/ledger-host-attestation.mjs');
  assert.ok(cg.hooks[0].command.includes('${PLUGIN_ROOT}/scripts/ledger-host-attestation.mjs'));
  for (const host of ['codex', 'antigravity']) {
    assert.ok(readFileSync(resolve(root, `clients/${host}/scripts/ledger-host-attestation.mjs`)).length > 0);
    assert.equal(JSON.parse(readFileSync(resolve(root, `clients/${host}/plugin.json`))).name, 'pg-ledger-host-attestation');
  }
});
test('S09: documented Antigravity MCP naming candidates are distinguished', () => {
  const a = adapters[1];
  for (const name of ['mcp:ledger_save', 'mcp:test:ledger_save', 'mcp:test/ledger_save', 'mcp:test_ledger_save']) {
    assert.equal(a.decision(invoke(a, a.event(name, {}))), 'allow');
  }
});
test('S10: exposed Codex app namespace names and native matcher cover all ten suffixes', () => {
  const a = adapters[0];
  const config = JSON.parse(readFileSync(resolve(root, 'clients/codex/hooks/hooks.json')));
  const matcher = new RegExp(config.hooks.PreToolUse[0].matcher);
  for (const op of tools) {
    const name = `mcp__codex_apps__app_ledger_ledger_${op}`;
    assert.ok(matcher.test(name));
    const out = invoke(a, a.event(name, { marker: 'T0001-namespace' }));
    assert.equal(a.args({}, out)._hostAttestation, `codex:${id}`);
    assert.equal(a.decision(invoke(a, a.event(name, { _hostAttestation: null }))), 'deny');
  }
  assert.equal(invoke(a, a.event('mcp__codex_apps__app_ledger_ledger_get', {})), null);
});
test('S11: Antigravity call_mcp_tool targets only ledger Arguments and preserves envelope', () => {
  const a = adapters[1];
  const config = JSON.parse(readFileSync(resolve(root, 'clients/antigravity/hooks.json')));
  assert.ok(new RegExp(config['pg-ledger-host-attestation'].PreToolUse[0].matcher).test('call_mcp_tool'));
  for (const stringify of [false, true]) {
    const encode = value => stringify ? JSON.stringify(value) : value;
    for (const op of tools) {
      const input = { marker: 'T0001-wrapper', author_id: 'model:declared', nested: { keep: [1, null, false] } };
      const envelope = { ServerName: 't0001sink', ToolName: `app_ledger_ledger_${op}`, Arguments: encode(input), extra: { keep: true } };
      const out = invoke(a, a.event('call_mcp_tool', envelope));
      assert.equal(a.decision(out), 'allow');
      const actualEnvelope = { ...envelope, ...out.overwrite };
      const actual = stringify ? JSON.parse(actualEnvelope.Arguments) : actualEnvelope.Arguments;
      assert.deepEqual(actualEnvelope.extra, envelope.extra);
      assert.equal(actualEnvelope.ServerName, envelope.ServerName);
      assert.equal(actualEnvelope.ToolName, envelope.ToolName);
      assert.deepEqual(Object.keys(out.overwrite), ['Arguments']);
      assert.deepEqual(actual, { ...input, _hostAttestation: `gemini:${id}` });
      for (const value of [null, '', false, {}, [], `gemini:${id}`]) {
        const args = { ...envelope, Arguments: encode({ _hostAttestation: value }) };
        assert.equal(a.decision(invoke(a, a.event('call_mcp_tool', args))), 'deny');
      }
    }
  }
  for (const ToolName of ['ledger_get', 'unrelated_save', 'ledger_save_extra']) {
    assert.equal(invoke(a, a.event('call_mcp_tool', { ServerName: 'other', ToolName, Arguments: { _hostAttestation: null } }, null)), null);
  }
  for (const Arguments of [null, [], 7, '{', '[]', 'null', '"text"']) {
    assert.equal(a.decision(invoke(a, a.event('call_mcp_tool', { ServerName: 't0001sink', ToolName: 'ledger_save', Arguments }))), 'deny');
  }
});
