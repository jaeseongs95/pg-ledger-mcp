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
  let r;
  try { r = await mcp.call(tool,args); }
  catch (e) { if (!e.message.startsWith('tools/call:')) throw e; r = { isError: true,data: e.message }; }
  const message = JSON.stringify(r.data);
  check(name,r.isError && [needle].flat().some(n => message.includes(n)),r.data);
};
const sql = async (query) => rowsOf((await mcp.call('execute_sql_reader', { sql: query })).data);

const names = await mcp.tools();
check('tool list has every ledger tool', LEDGER_TOOLS.every((t) => names.includes(t)), names);
check('no raw SQL tool on the writing logins', !names.some((t) => /^(execute_sql|search_objects)_(author|reviewer)$/.test(t)), names);

const defect = (title) => JSON.stringify({ project_id: project, title, category: 'BUG', severity: 'P2', behavior: 'observed behaviour', root_cause: 'not known yet' });
const reason = 'e2e: exercise the ledger through MCP';

// who may write
await refused('ungranted author is refused', 'ledger_save', { _hostAttestation: NOGRANT, entity: 'defect', expected_version: 0, fields: defect('x'), reason }, 'SESSION_NOT_GRANTED');
await refused('author id without session is refused', 'ledger_save', { _hostAttestation: 'claude', entity: 'defect', expected_version: 0, fields: defect('x'), reason }, 'AUTHOR_ID_FORMAT');
await refused('user:<other> is refused', 'ledger_save', { _hostAttestation: 'user:someone', entity: 'defect', expected_version: 0, fields: defect('x'), reason }, 'AUTHOR_ID_FORMAT');
await ok('whoami lists the grants', 'ledger_whoami', { _hostAttestation: AUTHOR }, (w) => w.author_format_ok === true && w.grants.length === 4);

// Common codes are carried in real tools/list descriptions and allowed_values.
const definitions = await mcp.toolDefinitions();
const definition = (name) => definitions.find(t => t.name === name);
check('K1 ledger_codes is removed',!names.includes('ledger_codes'),names);
const codes = await sql('SELECT code_group,code,name_ko,name_en,description_ko,description_en,active FROM ledger.common_code ORDER BY code_group,sort_order,code');
check('K1 only the three extensible groups exist',JSON.stringify([...new Set(codes.map(c => c.code_group))].sort()) ===
  JSON.stringify(['DEFECT_CATEGORY','DEFECT_SEVERITY','RELATION_KIND']),codes);
check('K1 every active category is rendered with escaped text preserved',codes.filter(c => c.code_group === 'DEFECT_CATEGORY' && c.active).every(c => definition('ledger_save').description.includes(`${c.code} ${c.name_ko} (${c.name_en}): ${c.description_ko} / ${c.description_en}`)),definition('ledger_save'));
check('K1 bilingual required severities are rendered, inactive UNRATED is omitted',
  codes.filter(c => c.code_group === 'DEFECT_SEVERITY' && c.active).every(c => definition('ledger_save').description.includes(`${c.code} ${c.name_ko} (${c.name_en}): ${c.description_ko} / ${c.description_en}`))
  && !definition('ledger_save').description.includes('UNRATED 미분류'),definition('ledger_save'));
const relationKinds = ['defect_task','condition_task','condition_defect',...codes.filter(c => c.code_group === 'RELATION_KIND' && c.active).map(c => c.code)];
check('K2 relation allowed_values equal active DB codes',JSON.stringify(definition('ledger_link').inputSchema.properties.kind.enum) === JSON.stringify(relationKinds),definition('ledger_link'));
for (const t of definitions.filter(t => /^ledger_/.test(t.name))) {
  check(`H1 ${t.name} exposes optional reserved attestation and no author_id`,
    t.inputSchema.properties._hostAttestation?.type === 'string' && !t.inputSchema.required?.includes('_hostAttestation')
    && !Object.hasOwn(t.inputSchema.properties,'author_id'),t.inputSchema);
}
await refused('H2 missing host attestation is refused','ledger_save',{ entity: 'defect',expected_version: 0,fields: defect('missing host'),reason },'HOST_ATTESTATION_REQUIRED');
await refused('H2 whoami requires host attestation','ledger_whoami',{},'HOST_ATTESTATION_REQUIRED');
// Antigravity overwrite merges arguments. A retained model author_id must not influence the SQL position.
await ok('H3 DBHub accepts legacy author_id but uses host attestation','ledger_whoami',
  { _hostAttestation: AUTHOR,author_id: NOGRANT },w => w.author === AUTHOR && w.grants.length === 4);
await refused('H3 legacy author_id alone cannot authorize','ledger_save',
  { author_id: AUTHOR,entity: 'defect',expected_version: 0,fields: defect('legacy only'),reason },'HOST_ATTESTATION_REQUIRED');
const noSeverity = JSON.parse(defect('no severity')); delete noSeverity.severity;
await refused('K3 a defect without severity is refused', 'ledger_save', { _hostAttestation: AUTHOR, entity: 'defect', expected_version: 0, fields: JSON.stringify(noSeverity), reason },
  'CODE_REQUIRED: defect.severity is required, one of: P1, P2, P3');
await refused('K4 the retired UNRATED is refused on a new defect', 'ledger_save', { _hostAttestation: AUTHOR, entity: 'defect', expected_version: 0,
  fields: JSON.stringify({ ...JSON.parse(defect('unrated')), severity: 'UNRATED' }), reason }, 'CODE_INVALID: defect.severity = UNRATED is not an active code of DEFECT_SEVERITY, use one of: P1, P2, P3');
await refused('K5 an unknown category is refused', 'ledger_save', { _hostAttestation: AUTHOR, entity: 'defect', expected_version: 0,
  fields: JSON.stringify({ ...JSON.parse(defect('typo')), category: 'BGU' }), reason }, 'CODE_INVALID: defect.category = BGU');

// create, ids, idempotency, optimistic lock
const rid = randomUUID();
const first = await ok('new defect gets the next id', 'ledger_save', { request_id: rid, _hostAttestation: AUTHOR, author_id: NOGRANT, entity: 'defect', expected_version: 0, fields: defect('first'), reason },
  (x) => x.author === AUTHOR && x.id === `${code}-D-0001` && x.version === 1);
await ok('same request id replays the stored result', 'ledger_save', { request_id: rid, _hostAttestation: AUTHOR, entity: 'defect', expected_version: 0, fields: defect('first'), reason },
  (x) => x.history_id === result(first).history_id);
await refused('same request id with another payload is refused', 'ledger_save', { request_id: rid, _hostAttestation: AUTHOR, entity: 'defect', expected_version: 0, fields: defect('other'), reason }, 'already bound');
await ok('second defect gets the following id', 'ledger_save', { _hostAttestation: AUTHOR, entity: 'defect', expected_version: 0, fields: defect('second'), reason }, (x) => x.id === `${code}-D-0002`);
await refused('defect id with a foreign prefix is refused', 'ledger_save', { _hostAttestation: AUTHOR, entity: 'defect', id: 'OTHER-D-0009', expected_version: 0, fields: defect('x'), reason }, 'must start with');
await refused('stale version is refused', 'ledger_save', { _hostAttestation: AUTHOR, entity: 'defect', id: `${code}-D-0001`, expected_version: 7, fields: JSON.stringify({ title: 'edited' }), reason }, 'version conflict');
await ok('edit with the current version', 'ledger_save', { _hostAttestation: AUTHOR, entity: 'defect', id: `${code}-D-0001`, expected_version: 1, fields: JSON.stringify({ title: 'first, edited' }), reason }, (x) => x.version === 2);
await refused('a defect cannot change project', 'ledger_save', { _hostAttestation: AUTHOR, entity: 'defect', id: `${code}-D-0001`, expected_version: 2, fields: JSON.stringify({ project_id: 'ELSEWHERE' }), reason }, 'cannot move');

// fix and verification
await refused('K6 an unknown state is refused', 'ledger_transition', { _hostAttestation: AUTHOR, entity: 'defect', id: `${code}-D-0002`, expected_version: 1, to: 'FIXED', reason },
  'to: Invalid option: expected one of');
await refused('K7 an unknown link kind is refused, naming the relation kinds', 'ledger_link', { _hostAttestation: AUTHOR, kind: 'related', a: `${code}-D-0001`, b: `${code}-D-0002`, reason },
  'kind: Invalid option: expected one of');
await refused('K8 an unknown CI conclusion is refused', 'ledger_record_fix', { _hostAttestation: AUTHOR, defect_id: `${code}-D-0002`, repo: 'example/repo', commit_sha: SHA, reason,
  ci_ref: 'https://ci.example/run/9', ci_conclusion: 'passed' }, 'ci_conclusion: Invalid option: expected one of');
await refused('SOURCE_FIXED without a fix is refused', 'ledger_transition', { _hostAttestation: AUTHOR, entity: 'defect', id: `${code}-D-0002`, expected_version: 1, to: 'SOURCE_FIXED', reason }, 'needs a recorded fix');
await ok('record_fix moves OPEN to SOURCE_FIXED', 'ledger_record_fix', { _hostAttestation: AUTHOR, defect_id: `${code}-D-0001`, repo: 'example/repo', commit_sha: SHA, reason,
  ci_ref: 'https://ci.example/run/1', ci_conclusion: 'success', ci_completed_at: new Date().toISOString() }, (x) => x.defect_status === 'SOURCE_FIXED' && x.version === 3);
await refused('the author tool cannot set VERIFIED', 'ledger_transition', { _hostAttestation: AUTHOR, entity: 'defect', id: `${code}-D-0001`, expected_version: 3, to: 'VERIFIED', reason, evidence_uri: 'https://ci.example/run/1' }, 'to: Invalid option: expected one of');
await refused('the fixing session cannot verify its own fix', 'ledger_review', { _hostAttestation: AUTHOR, entity: 'defect', id: `${code}-D-0001`, expected_version: 3, to: 'VERIFIED', reason, evidence_uri: 'https://ci.example/run/1' }, 'SAME_SESSION');
await refused('verify needs a verify grant', 'ledger_review', { _hostAttestation: NOGRANT, entity: 'defect', id: `${code}-D-0001`, expected_version: 3, to: 'VERIFIED', reason, evidence_uri: 'https://ci.example/run/1' }, 'SESSION_NOT_GRANTED');
await ok('another session verifies through the reviewer login', 'ledger_review', { _hostAttestation: REVIEWER, entity: 'defect', id: `${code}-D-0001`, expected_version: 3, to: 'VERIFIED', reason, evidence_uri: 'https://ci.example/run/1' }, (x) => x.to === 'VERIFIED' && x.login === 'ledger_reviewer');

// links and corrections
await ok('link two defects', 'ledger_link', { _hostAttestation: AUTHOR, kind: 'same_family', a: `${code}-D-0002`, b: `${code}-D-0001`, reason }, (x) => x.id === `${code}-D-0001|${code}-D-0002|same_family`);
const [{ id: createRow }] = await sql(`SELECT id FROM ledger.history WHERE entity = 'defect' AND entity_id = '${code}-D-0002' AND op = 'CREATE'`);
await ok('correct a history row', 'ledger_correct', { _hostAttestation: AUTHOR, history_id: Number(createRow), reason: 'e2e: the title was a placeholder' }, (x) => x.corrects === Number(createRow));

// batch: one transaction, replay by batch request id, all-or-nothing, same rules as the single tools
const batch = (ops, extra = {}) => ({ _hostAttestation: AUTHOR, ops: JSON.stringify(ops), ...extra });
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
await refused('batch needs the same grants', 'ledger_batch', { _hostAttestation: NOGRANT, ops: JSON.stringify([saveOp('x')]) }, 'SESSION_NOT_GRANTED');
await refused('batch cannot verify through the author login', 'ledger_batch',
  batch([{ op: 'transition', entity: 'defect', id: `${code}-D-0004`, expected_version: 1, to: 'VERIFIED', reason, evidence_uri: 'https://ci.example/run/1' }]), 'VERIFY_NEEDS_REVIEWER_LOGIN');
await refused('batch refuses unknown keys', 'ledger_batch', batch([{ ...saveOp('x'), titel: 'typo' }]), 'unknown key(s) titel');
await refused('batch refuses a missing version on transition', 'ledger_batch',
  batch([{ op: 'transition', entity: 'defect', id: `${code}-D-0004`, to: 'OPEN', reason }]), 'missing expected_version');

// observed cases: one defect = one root cause, its observations are rows under it
const caseArgs = (defectId, observation, extra = {}) => ({ _hostAttestation: AUTHOR, defect_id: defectId, observation, reason: 'e2e: record an observed case', ...extra });
const D1 = `${code}-D-0001`, D2 = `${code}-D-0002`, D3 = `${code}-D-0003`, D4 = `${code}-D-0004`;
const cid = randomUUID();
const c1 = await ok('C1 record a case with evidence, user quote and time', 'ledger_record_case',
  caseArgs(D1, 'login page shows 500', { request_id: cid, evidence: 'req 7f3a; app/login.ts:42', user_quote: '로그인이 안 돼요', observed_at: '2026-11-05T10:00:00Z' }),
  (x) => x.entity === 'defect_case' && x.defect === D1 && x.id === `${D1}|${x.case_id}`);
const c1h = result(c1)?.history_id;
const [c1row] = (await sql(`SELECT entity, entity_id, op, author_id, login FROM ledger.history WHERE id = ${Number(c1h)}`)) ?? [];
check('C2 the case has a history row', c1row?.entity === 'defect_case' && c1row?.op === 'CREATE' && c1row?.author_id === AUTHOR && c1row?.login === 'ledger_author', c1row);
await refused('C3 a blank observation is refused', 'ledger_record_case', caseArgs(D1, '   '), 'defect_case_observation_check');
await refused('C4 an ungranted author cannot record a case', 'ledger_record_case', { ...caseArgs(D1, 'x'), _hostAttestation: NOGRANT }, 'SESSION_NOT_GRANTED');
await refused('C5 a case on an unknown defect is refused', 'ledger_record_case', caseArgs(`${code}-D-9999`, 'x'), 'not found');
await ok('C6 same request id replays the stored case', 'ledger_record_case',
  caseArgs(D1, 'login page shows 500', { request_id: cid, evidence: 'req 7f3a; app/login.ts:42', user_quote: '로그인이 안 돼요', observed_at: '2026-11-05T10:00:00Z' }),
  (x) => x.history_id === c1h);
await refused('C7 same request id with another observation is refused', 'ledger_record_case', caseArgs(D1, 'something else', { request_id: cid }), 'already bound');
const caseCount = async () => Number((await sql('SELECT count(*) AS n FROM ledger.defect_case'))?.[0]?.n);
check('C6 the replay wrote no second case', (await caseCount()) === 1, await caseCount());

const c8id = randomUUID();
const c8 = await ok('C8 record a case on a live defect', 'ledger_record_case', caseArgs(D4, 'batch two crashed', { request_id: c8id }), (x) => x.defect === D4);
await ok('C8 merge it into another defect', 'ledger_transition', { _hostAttestation: AUTHOR, entity: 'defect', id: D4, expected_version: 1, to: 'MERGED', merged_into: D3, reason }, (x) => x.to === 'MERGED');
await refused('C8 a case on the merged defect is refused, naming the target', 'ledger_record_case', caseArgs(D4, 'seen again'), `MERGED_DEFECT: ${D4} was merged into ${D3}`);
await ok('C9 a retry of a case recorded before the merge replays', 'ledger_record_case', caseArgs(D4, 'batch two crashed', { request_id: c8id }),
  (x) => x.history_id === result(c8)?.history_id);
const caseOp = (defectId, observation, extra = {}) => ({ op: 'record_case', defect_id: defectId, observation, reason: 'e2e: record an observed case', ...extra });
await ok('C10 batch records cases', 'ledger_batch', batch([caseOp(D3, 'batch one crashed', { observed_at: '2026-11-01T00:00:00Z' }), caseOp(D3, 'batch one crashed again')]),
  (x) => x.count === 2 && x.results.every((r) => r.entity === 'defect_case' && r.defect === D3));
const before11 = await caseCount();
await refused('C11 a batch case on a merged defect rolls the batch back', 'ledger_batch', batch([caseOp(D3, 'must not stay'), caseOp(D4, 'merged')]),
  `batch op 2 (record_case): MERGED_DEFECT: ${D4} was merged into ${D3}`);
check('C11 nothing of that batch was written', (await caseCount()) === before11, await caseCount());

const casesOf = async (id) => (await sql(`SELECT cases FROM ledger.v_defect WHERE id = '${id}'`))?.[0]?.cases;
const tag = (cs) => (cs ?? []).map((c) => `${c.source}:${c.defect}`).sort();
const c12 = await casesOf(D3);
check('C12 the view lists the cases of the defect and of the defect merged into it', JSON.stringify(tag(c12)) ===
  JSON.stringify([`case:${D3}`, `case:${D3}`, `case:${D4}`, `merged_behavior:${D4}`]), c12);
await ok('C13 merge the target again (chain)', 'ledger_transition', { _hostAttestation: AUTHOR, entity: 'defect', id: D3, expected_version: 1, to: 'MERGED', merged_into: D2, reason }, (x) => x.to === 'MERGED');
const c13 = await casesOf(D2);
check('C13 the view follows chains of merges', JSON.stringify(tag(c13)) ===
  JSON.stringify([`case:${D3}`, `case:${D3}`, `case:${D4}`, `merged_behavior:${D3}`, `merged_behavior:${D4}`]), c13);
const c14 = await casesOf(D1);
check('C14 the view keeps evidence, user quote and observation time', c14?.length === 1 && c14[0].evidence === 'req 7f3a; app/login.ts:42'
  && c14[0].user_quote === '로그인이 안 돼요' && Date.parse(c14[0].observed_at) === Date.parse('2026-11-05T10:00:00Z') && c14[0].by === AUTHOR, c14);
await ok('C15 a case history row can be corrected', 'ledger_correct', { _hostAttestation: AUTHOR, history_id: Number(c1h), reason: 'e2e: wrong page named in the case' },
  (x) => x.corrects === Number(c1h));

// Provenance uses the attested host id for save, links and batch; mutable fields cannot forge it.
await refused('P1 save fields cannot set created_by','ledger_save',{ _hostAttestation: AUTHOR,entity: 'defect',id: D2,expected_version: 1,
  fields: JSON.stringify({ created_by: 'model:forged' }),reason },'created_by not editable');
await ok('P2 create a task and condition in one batch','ledger_batch',batch([
  { op: 'save',entity: 'task',id: 'E2E-T-1',fields: { project_id: project,title: 'Task',purpose: 'prove provenance',success_condition: 'stamped' },reason },
  { op: 'save',entity: 'condition',id: 'E2E-C-1',fields: { project_id: project,release: 'test',code: 'C1',condition_text: 'stamped' },reason },
  { op: 'link',kind: 'defect_task',a: D2,b: 'E2E-T-1',reason },
  { op: 'link',kind: 'condition_task',a: 'E2E-C-1',b: 'E2E-T-1',reason },
  { op: 'link',kind: 'condition_defect',a: 'E2E-C-1',b: D2,reason }
]),x => x.count === 5);
for (const table of ['task','defect','condition','defect_task','defect_relation','condition_link']) {
  const provenance = await sql(`SELECT created_by FROM ledger.${table} ${['task','defect','condition'].includes(table) ? `WHERE project_id = '${project}'` : ''}`);
  check(`P3 ${table} rows keep their first host author`,provenance.length > 0 && provenance.every(r => r.created_by === AUTHOR || r.created_by === 'claude:e2e-seed'),provenance);
}
const missingHostCases = {
  ledger_transition: { entity: 'defect',id: D2,expected_version: 1,to: 'DISPUTED',reason,evidence_uri: 'https://example.test/evidence' },
  ledger_record_fix: { defect_id: D2,repo: 'example/repo',commit_sha: 'b'.repeat(40),reason },
  ledger_record_case: { defect_id: D2,observation: 'missing host',reason },
  ledger_link: { kind: 'same_site',a: D1,b: D2,reason },
  ledger_correct: { history_id: Number(createRow),reason },
  ledger_batch: { ops: JSON.stringify([saveOp('missing host')]) },
  ledger_review: { entity: 'defect',id: D1,expected_version: 4,to: 'OPEN',reason,evidence_uri: 'https://example.test/evidence' },
  ledger_review_correct: { history_id: Number(createRow),reason }
};
for (const [tool,args] of Object.entries(missingHostCases)) await refused(`H2 ${tool} requires host attestation`,tool,args,'HOST_ATTESTATION_REQUIRED');
const [fixAuthor] = await sql(`SELECT recorded_by FROM ledger.defect_fix WHERE defect_id='${D1}'`);
check('P4 record_fix keeps its existing author column',fixAuthor?.recorded_by === AUTHOR,fixAuthor);
const [firstAuthor] = await sql(`SELECT created_by FROM ledger.v_defect WHERE id='${D1}'`);
check('P5 edit, record_fix and reviewer transition preserve original creator',firstAuthor?.created_by === AUTHOR,firstAuthor);
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
