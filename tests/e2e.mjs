// e2e.mjs : drive a running pg-ledger-mcp endpoint through its MCP tools only.
//   DBHUB_AUTH_TOKEN=... node tests/e2e.mjs <mcp-url> <project-id> <project-code>
// Expects (tests/e2e.sh sets this up): project <project-id>/<project-code>, category 'BUG', and grants
//   claude:e2e-author   defect, task, fix, verify   (verify only to prove the same-session rule)
//   codex:e2e-reviewer  verify
// and no grant at all for gemini:e2e-nogrant.
import { randomUUID } from 'node:crypto';
import { connect, LEDGER_TOOLS } from '../bin/mcp-call.mjs';

const [url, project, code] = process.argv.slice(2);
if (!url || !project || !code) { console.error('usage: e2e.mjs <mcp-url> <project-id> <project-code>'); process.exit(2); }
const AUTHOR = 'claude:e2e-author', REVIEWER = 'codex:e2e-reviewer', NOGRANT = 'gemini:e2e-nogrant';
const SHA = 'a'.repeat(40);
const mcp = await connect(url, process.env.DBHUB_AUTH_TOKEN);

let failed = 0;
const check = (name, ok, detail) => {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${ok ? '' : `\n      ${JSON.stringify(detail).slice(0, 600)}`}`);
  if (!ok) failed++;
};
// first "rows" array anywhere in the tool response
const rowsOf = (x) => {
  if (x && typeof x === 'object') {
    if (Array.isArray(x.rows)) return x.rows;
    for (const v of Object.values(x)) { const r = rowsOf(v); if (r) return r; }
  }
  return null;
};
const result = (r) => rowsOf(r.data)?.[0]?.result;
const ok = async (name, tool, args, test = () => true) => {
  const r = await mcp.call(tool, args);
  check(name, !r.isError && test(result(r) ?? rowsOf(r.data)), r.data);
  return r;
};
const refused = async (name, tool, args, needle) => {
  const r = await mcp.call(tool, args);
  check(name, r.isError && JSON.stringify(r.data).includes(needle), r.data);
};
const sql = async (query) => rowsOf((await mcp.call('execute_sql_reader', { sql: query })).data);

const names = await mcp.tools();
check('tool list has every ledger tool', LEDGER_TOOLS.every((t) => names.includes(t)), names);
check('no raw SQL tool on the writing logins', !names.some((t) => /^(execute_sql|search_objects)_(author|reviewer)$/.test(t)), names);

const defect = (title) => JSON.stringify({ project_id: project, title, category: 'BUG', severity: 'P2', behavior: 'observed behaviour', root_cause: 'not known yet' });
const reason = 'e2e: exercise the ledger through MCP';

// who may write
await refused('ungranted author is refused', 'ledger_save', { author_id: NOGRANT, entity: 'defect', expected_version: 0, fields: defect('x'), reason }, 'SESSION_NOT_GRANTED');
await refused('author id without session is refused', 'ledger_save', { author_id: 'claude', entity: 'defect', expected_version: 0, fields: defect('x'), reason }, 'AUTHOR_ID_FORMAT');
await refused('user:<other> is refused', 'ledger_save', { author_id: 'user:someone', entity: 'defect', expected_version: 0, fields: defect('x'), reason }, 'AUTHOR_ID_FORMAT');
await ok('whoami lists the grants', 'ledger_whoami', { author_id: AUTHOR }, (w) => w.author_format_ok === true && w.grants.length === 4);

// create, ids, idempotency, optimistic lock
const rid = randomUUID();
const first = await ok('new defect gets the next id', 'ledger_save', { request_id: rid, author_id: AUTHOR, entity: 'defect', expected_version: 0, fields: defect('first'), reason },
  (x) => x.id === `${code}-D-0001` && x.version === 1);
await ok('same request id replays the stored result', 'ledger_save', { request_id: rid, author_id: AUTHOR, entity: 'defect', expected_version: 0, fields: defect('first'), reason },
  (x) => x.history_id === result(first).history_id);
await refused('same request id with another payload is refused', 'ledger_save', { request_id: rid, author_id: AUTHOR, entity: 'defect', expected_version: 0, fields: defect('other'), reason }, 'already bound');
await ok('second defect gets the following id', 'ledger_save', { author_id: AUTHOR, entity: 'defect', expected_version: 0, fields: defect('second'), reason }, (x) => x.id === `${code}-D-0002`);
await refused('defect id with a foreign prefix is refused', 'ledger_save', { author_id: AUTHOR, entity: 'defect', id: 'OTHER-D-0009', expected_version: 0, fields: defect('x'), reason }, 'must start with');
await refused('stale version is refused', 'ledger_save', { author_id: AUTHOR, entity: 'defect', id: `${code}-D-0001`, expected_version: 7, fields: JSON.stringify({ title: 'edited' }), reason }, 'version conflict');
await ok('edit with the current version', 'ledger_save', { author_id: AUTHOR, entity: 'defect', id: `${code}-D-0001`, expected_version: 1, fields: JSON.stringify({ title: 'first, edited' }), reason }, (x) => x.version === 2);
await refused('a defect cannot change project', 'ledger_save', { author_id: AUTHOR, entity: 'defect', id: `${code}-D-0001`, expected_version: 2, fields: JSON.stringify({ project_id: 'ELSEWHERE' }), reason }, 'cannot move');

// fix and verification
await refused('SOURCE_FIXED without a fix is refused', 'ledger_transition', { author_id: AUTHOR, entity: 'defect', id: `${code}-D-0002`, expected_version: 1, to: 'SOURCE_FIXED', reason }, 'needs a recorded fix');
await ok('record_fix moves OPEN to SOURCE_FIXED', 'ledger_record_fix', { author_id: AUTHOR, defect_id: `${code}-D-0001`, repo: 'example/repo', commit_sha: SHA, reason,
  ci_ref: 'https://ci.example/run/1', ci_conclusion: 'success', ci_completed_at: new Date().toISOString() }, (x) => x.defect_status === 'SOURCE_FIXED' && x.version === 3);
await refused('the author tool cannot set VERIFIED', 'ledger_transition', { author_id: AUTHOR, entity: 'defect', id: `${code}-D-0001`, expected_version: 3, to: 'VERIFIED', reason, evidence_uri: 'https://ci.example/run/1' }, 'Invalid option');  // refused by the tool schema (allowed_values) before SQL runs
await refused('the fixing session cannot verify its own fix', 'ledger_review', { author_id: AUTHOR, entity: 'defect', id: `${code}-D-0001`, expected_version: 3, to: 'VERIFIED', reason, evidence_uri: 'https://ci.example/run/1' }, 'SAME_SESSION');
await refused('verify needs a verify grant', 'ledger_review', { author_id: NOGRANT, entity: 'defect', id: `${code}-D-0001`, expected_version: 3, to: 'VERIFIED', reason, evidence_uri: 'https://ci.example/run/1' }, 'SESSION_NOT_GRANTED');
await ok('another session verifies through the reviewer login', 'ledger_review', { author_id: REVIEWER, entity: 'defect', id: `${code}-D-0001`, expected_version: 3, to: 'VERIFIED', reason, evidence_uri: 'https://ci.example/run/1' }, (x) => x.to === 'VERIFIED' && x.login === 'ledger_reviewer');

// links and corrections
await ok('link two defects', 'ledger_link', { author_id: AUTHOR, kind: 'same_family', a: `${code}-D-0002`, b: `${code}-D-0001`, reason }, (x) => x.id === `${code}-D-0001|${code}-D-0002|same_family`);
const [{ id: createRow }] = await sql(`SELECT id FROM ledger.history WHERE entity = 'defect' AND entity_id = '${code}-D-0002' AND op = 'CREATE'`);
await ok('correct a history row', 'ledger_correct', { author_id: AUTHOR, history_id: Number(createRow), reason: 'e2e: the title was a placeholder' }, (x) => x.corrects === Number(createRow));

// batch: one transaction, replay by batch request id, all-or-nothing, same rules as the single tools
const batch = (ops, extra = {}) => ({ author_id: AUTHOR, ops: JSON.stringify(ops), ...extra });
const saveOp = (title) => ({ op: 'save', entity: 'defect', fields: JSON.parse(defect(title)), reason });
const bid = randomUUID();
const b1 = await ok('batch saves two defects and links them', 'ledger_batch', batch([saveOp('batch one'), saveOp('batch two'),
  { op: 'link', kind: 'same_site', a: `${code}-D-0003`, b: `${code}-D-0004`, reason }], { request_id: bid }),
  (x) => x.count === 3 && x.results[0].id === `${code}-D-0003` && x.results[1].id === `${code}-D-0004` && x.results[2].entity === 'defect_relation');
await ok('same batch request id replays the stored results', 'ledger_batch', batch([saveOp('batch one'), saveOp('batch two'),
  { op: 'link', kind: 'same_site', a: `${code}-D-0003`, b: `${code}-D-0004`, reason }], { request_id: bid }),
  (x) => JSON.stringify(x.results) === JSON.stringify(result(b1).results));
await refused('a failing op rolls the whole batch back', 'ledger_batch', batch([saveOp('must not stay'),
  { op: 'transition', entity: 'defect', id: `${code}-D-0003`, expected_version: 9, to: 'DISPUTED', reason, evidence_uri: 'https://ci.example/run/2' }]), 'batch op 2 (transition): version conflict');
const [{ n: kept }] = await sql(`SELECT count(*) AS n FROM ledger.defect WHERE title = 'must not stay'`);
check('nothing of the failed batch was written', Number(kept) === 0, kept);
await refused('batch needs the same grants', 'ledger_batch', { author_id: NOGRANT, ops: JSON.stringify([saveOp('x')]) }, 'SESSION_NOT_GRANTED');
await refused('batch cannot verify through the author login', 'ledger_batch',
  batch([{ op: 'transition', entity: 'defect', id: `${code}-D-0004`, expected_version: 1, to: 'VERIFIED', reason, evidence_uri: 'https://ci.example/run/1' }]), 'VERIFY_NEEDS_REVIEWER_LOGIN');
await refused('batch refuses unknown keys', 'ledger_batch', batch([{ ...saveOp('x'), titel: 'typo' }]), 'unknown key(s) titel');
await refused('batch refuses a missing version on transition', 'ledger_batch',
  batch([{ op: 'transition', entity: 'defect', id: `${code}-D-0004`, to: 'OPEN', reason }]), 'missing expected_version');

// read side
const [row] = await sql(`SELECT status, version, project_id FROM ledger.v_defect WHERE id = '${code}-D-0001'`);
check('reader sees the verified defect', row?.status === 'VERIFIED' && row?.project_id === project, row);
const hist = await sql(`SELECT author_id, login, op, to_state FROM ledger.history WHERE entity_id = '${code}-D-0001' AND entity = 'defect' ORDER BY id`);
check('history keeps who, which login and what', hist?.length === 4 && hist[3].author_id === REVIEWER && hist[3].login === 'ledger_reviewer' && hist[2].to_state === 'SOURCE_FIXED', hist);
const w = await mcp.call('execute_sql_reader', { sql: `UPDATE ledger.defect SET title = 'x' WHERE id = '${code}-D-0001'` });
check('reader cannot write', w.isError, w.data);
const h = await mcp.call('execute_sql_reader', { sql: 'SELECT ledger.grant_session(\'claude:x\', \'x\', \'defect\')' });
check('reader cannot grant', h.isError, h.data);

console.log(failed ? `${failed} check(s) FAILED` : 'ALL PASSED');
process.exit(failed ? 1 : 0);
