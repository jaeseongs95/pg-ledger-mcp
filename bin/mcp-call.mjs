#!/usr/bin/env node
// mcp-call.mjs : minimal MCP (Streamable HTTP) client for checks and scripts. Node >= 18, no dependencies.
//   DBHUB_AUTH_TOKEN=... node bin/mcp-call.mjs <url> check               tool list must contain every ledger tool
//   DBHUB_AUTH_TOKEN=... node bin/mcp-call.mjs <url> list                print tool names
//   DBHUB_AUTH_TOKEN=... node bin/mcp-call.mjs <url> call <tool> '<json>' print the tool result; exit 1 when it is an error
import { pathToFileURL } from 'node:url';

export const LEDGER_TOOLS = ['execute_sql_reader', 'search_objects_reader', 'ledger_whoami', 'ledger_save', 'ledger_transition',
  'ledger_record_fix', 'ledger_record_case', 'ledger_link', 'ledger_correct', 'ledger_batch', 'ledger_review', 'ledger_review_correct'];

export async function connect(url, token) {
  let sessionId;
  let nextId = 1;
  async function post(body) {
    const headers = { 'content-type': 'application/json', accept: 'application/json, text/event-stream' };
    if (token) headers.authorization = `Bearer ${token}`;
    if (sessionId) headers['mcp-session-id'] = sessionId;
    const res = await fetch(url, { method: 'POST', headers, body: JSON.stringify(body) });
    sessionId = res.headers.get('mcp-session-id') ?? sessionId;
    const text = await res.text();
    if (!res.ok) throw new Error(`HTTP ${res.status}: ${text.slice(0, 500)}`);
    if (body.id === undefined) return null;
    const type = res.headers.get('content-type') ?? '';
    const messages = type.includes('text/event-stream')
      ? text.split('\n').filter((l) => l.startsWith('data:')).map((l) => JSON.parse(l.slice(5)))
      : [JSON.parse(text)];
    const reply = messages.find((m) => m.id === body.id);
    if (!reply) throw new Error(`no reply to ${body.method}: ${text.slice(0, 500)}`);
    if (reply.error) throw new Error(`${body.method}: ${JSON.stringify(reply.error)}`);
    return reply.result;
  }
  const request = (method, params) => post({ jsonrpc: '2.0', id: nextId++, method, params });
  await request('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'pg-ledger-mcp-check', version: '1' } });
  await post({ jsonrpc: '2.0', method: 'notifications/initialized' });
  return {
    async tools() { return (await request('tools/list', {})).tools.map((t) => t.name); },
    // returns { isError, data }: data is the parsed JSON text content when possible
    async call(name, args) {
      const r = await request('tools/call', { name, arguments: args });
      const text = (r.content ?? []).filter((c) => c.type === 'text').map((c) => c.text).join('\n');
      let data = text;
      try { data = JSON.parse(text); } catch { /* plain text */ }
      return { isError: Boolean(r.isError), data };
    },
  };
}

async function main([url, cmd, ...rest]) {
  if (!url || !['check', 'list', 'call'].includes(cmd)) {
    console.error('usage: mcp-call.mjs <url> check | list | call <tool> <json-arguments>');
    return 2;
  }
  const client = await connect(url, process.env.DBHUB_AUTH_TOKEN);
  if (cmd === 'call') {
    const r = await client.call(rest[0], JSON.parse(rest[1] ?? '{}'));
    console.log(typeof r.data === 'string' ? r.data : JSON.stringify(r.data, null, 2));
    return r.isError ? 1 : 0;
  }
  const names = await client.tools();
  if (cmd === 'list') { console.log(names.join('\n')); return 0; }
  const missing = LEDGER_TOOLS.filter((t) => !names.includes(t));
  if (missing.length) { console.error(`missing tools: ${missing.join(', ')} (got: ${names.join(', ')})`); return 1; }
  console.log(`MCP tools OK: ${names.length} tools (${LEDGER_TOOLS.length} ledger tools present)`);
  return 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main(process.argv.slice(2)).then((code) => process.exit(code), (e) => { console.error(e.message); process.exit(1); });
}
