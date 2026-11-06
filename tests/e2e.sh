#!/usr/bin/env bash
# e2e.sh : install into a throwaway database, drive it through MCP, re-run the installer, clean up.
#   sudo tests/e2e.sh --pg-port <port of a DISPOSABLE cluster> --disposable-cluster [--mcp-port 18765] [--keep] [--upgrade-from <git-ref>]
# The installer resets the passwords of the cluster-wide ledger_* logins, so never point this at a cluster
# that serves a real ledger. Example for Debian/Ubuntu: pg_createcluster 16 e2e --port 5544 --start
# --upgrade-from: install <git-ref> of this repository first, write data with it, install this checkout over it and
# check that every existing row is unchanged; the scenario then runs on the upgraded database.
set -euo pipefail
REPO=$(cd "$(dirname "$0")/.." && pwd)
PG_PORT= MCP_PORT=18765 KEEP=0 DISPOSABLE=0 UPGRADE_FROM= OLD=
while [ $# -gt 0 ]; do
  case "$1" in
    --pg-port) PG_PORT=${2:?}; shift 2 ;;
    --mcp-port) MCP_PORT=${2:?}; shift 2 ;;
    --keep) KEEP=1; shift ;;
    --disposable-cluster) DISPOSABLE=1; shift ;;
    --upgrade-from) UPGRADE_FROM=${2:?}; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[ -n "$PG_PORT" ] && [ "$DISPOSABLE" = 1 ] || { sed -n '2,7p' "$0"; exit 2; }
[ "$(id -u)" = 0 ] || { echo "run as root" >&2; exit 1; }

NAME=pgledger-e2e
DB=pgledger_e2e_$(date -u +%Y%m%d%H%M%S)
PROJECT=e2e CODE=E2E
EVIDENCE_DIR=$REPO/../e2e-evidence-$DB
mkdir -p "$EVIDENCE_DIR"
ATOMIC_DIR=
pgsu() { runuser -u postgres -- psql -X -q -v ON_ERROR_STOP=1 -p "$PG_PORT" "$@"; }
cleanup() {
  [ "$KEEP" = 1 ] && { echo "kept: database $DB, unit $NAME, /etc/$NAME"; return; }
  # Only this harness's own database/process names are reconciled before dropping its disposable database.
  pgsu -d "$DB" -tAc "select pg_terminate_backend(pid) from pg_stat_activity where datname=current_database()
    and application_name in ('e2e-migration-gate','e2e-atomic-upgrade','e2e-old-category','e2e-old-severity')" >/dev/null 2>&1 || true
  if [ -n "$ATOMIC_DIR" ]; then
    for log in "$ATOMIC_DIR"/*.log; do [ ! -f "$log" ] || cp "$log" "$EVIDENCE_DIR/interrupted-$(basename "$log")"; done
  fi
  echo "TEST evidence: $EVIDENCE_DIR"
  systemctl disable --now "$NAME.service" >/dev/null 2>&1 || true
  rm -f "/etc/systemd/system/$NAME.service"; systemctl daemon-reload
  rm -rf "/etc/$NAME" "/var/backups/$NAME"
  pgsu -d postgres -c "DROP DATABASE IF EXISTS $DB" || true
  if [ -n "$OLD" ]; then rm -rf "$OLD"; fi
}
trap cleanup EXIT

fail=0
pass_if() { if [ "$2" = "$3" ]; then echo "PASS  $1"; else echo "FAIL  $1 (got: $2, want: $3)"; fail=1; fi; }  # <name> <got> <want>
admin() { "$REPO/bin/ledger-admin" --name "$NAME" "$@" >/dev/null; }
# psql through a ledger login over TCP, with its password from /etc/$NAME/env
login_sql() {  # <login> <sql>
  local pw
  pw=$(sed -n "s/^PGLEDGER_$(echo "$1" | sed 's/ledger_//' | tr a-z A-Z)_PASSWORD=//p" "/etc/$NAME/env")
  PGPASSWORD=$pw psql -X -q -v ON_ERROR_STOP=1 -h 127.0.0.1 -p "$PG_PORT" -U "$1" -d "$DB" -tAc "$2"
}
# digest of every row of every ledger table, except install bookkeeping (_migration), tables added later and
# category (a table before common_code, a view of it after; compared on its own)
snapshot() {
  pgsu -d "$DB" -tA <<'SQL'
SELECT string_agg(t.tablename || '=' || (xpath('/row/h/text()', query_to_xml(format(
         'SELECT md5(coalesce(string_agg((to_jsonb(x)-''created_by'')::text, E''\n'' ORDER BY (to_jsonb(x)-''created_by'')::text), '''')) AS h FROM ledger.%I x', t.tablename), false, true, '')))[1]::text,
       ' ' ORDER BY t.tablename)
FROM pg_tables t WHERE t.schemaname = 'ledger' AND t.tablename NOT IN ('_migration', 'common_code', 'common_code_use', 'category');
SQL
}

pgsu -d postgres -c "CREATE DATABASE $DB"
if [ -z "$UPGRADE_FROM" ]; then
  echo "== install (fresh)"
  "$REPO/deploy/install.sh" --db "$DB" --pg-port "$PG_PORT" --mcp-port "$MCP_PORT" --name "$NAME" --reset-passwords
else
  OLD=$(mktemp -d)
  git -c safe.directory="$REPO" -C "$REPO" archive "$UPGRADE_FROM" | tar -x -C "$OLD"   # sudo: the checkout may belong to another user
  echo "== install $UPGRADE_FROM (old schema)"
  "$OLD/deploy/install.sh" --db "$DB" --pg-port "$PG_PORT" --mcp-port "$MCP_PORT" --name "$NAME" --reset-passwords
  echo "== write data with the old schema"
  old_admin() { "$OLD/bin/ledger-admin" --name "$NAME" "$@" >/dev/null; }
  old_admin project-add old OLD "Data written before the upgrade"
  old_admin category-add OLDBUG "Old bug" "Category written before the upgrade"
  old_admin grant claude:e2e-seed old defect task --note "e2e seed"
  seed=
  for t in target merged; do
    seed+="SELECT ledger.save(gen_random_uuid(), 'claude:e2e-seed', 'defect', NULL, 0, jsonb_build_object('project_id', 'old', 'title', '$t',
      'category', 'OLDBUG', 'severity', 'P2', 'behavior', 'old $t behaviour', 'root_cause', 'old cause'), 'e2e: seed before the upgrade');"
  done
  seed+="SELECT ledger.transition(gen_random_uuid(), 'claude:e2e-seed', 'defect', 'OLD-D-0002', 1, 'MERGED', 'e2e: seed merge before the upgrade',
    NULL, NULL, 'OLD-D-0001');"
  seed+="SELECT ledger.save(gen_random_uuid(), 'claude:e2e-seed', 'defect', NULL, 0, jsonb_build_object('project_id', 'old', 'title', 'unrated',
      'category', 'OLDBUG', 'severity', 'UNRATED', 'behavior', 'old unrated behaviour', 'root_cause', 'old cause'), 'e2e: seed an unrated defect');"
  login_sql ledger_author "$seed" >/dev/null
  pass_if 'production baseline has defect_case' "$(pgsu -d "$DB" -tAc "select to_regclass('ledger.defect_case') is not null")" t
  # Clean canonical category text supplied by the parent; all writes still use the old checkout admin.
  node --input-type=module - "$OLD" "$REPO/tests/category-legacy-fixture.json" "$NAME" <<'JS'
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
for (const c of JSON.parse(readFileSync(process.argv[3],'utf8'))) {
  const r = spawnSync(`${process.argv[2]}/bin/ledger-admin`,['--name',process.argv[4],'category-add',c.code,c.name,c.definition],{ encoding: 'utf8' });
  if (r.status !== 0) throw new Error(`old category seed failed: ${c.code}: ${r.stderr}`);
}
JS
  login_sql ledger_author "SELECT ledger.save(gen_random_uuid(),'claude:e2e-seed','task','OLD-T-1',0,
    '{\"project_id\":\"old\",\"title\":\"Old task\",\"purpose\":\"preserve\",\"success_condition\":\"same row\"}', 'e2e: old task provenance');
    SELECT ledger.save(gen_random_uuid(),'claude:e2e-seed','condition','OLD-C-1',0,
    '{\"project_id\":\"old\",\"release\":\"old\",\"code\":\"C1\",\"condition_text\":\"Old condition\"}', 'e2e: old condition provenance');
    SELECT ledger.link(gen_random_uuid(),'claude:e2e-seed','defect_task','OLD-D-0001','OLD-T-1','e2e: old task link');
    SELECT ledger.link(gen_random_uuid(),'claude:e2e-seed','same_site','OLD-D-0001','OLD-D-0002','e2e: old relation link');
    SELECT ledger.link(gen_random_uuid(),'claude:e2e-seed','condition_task','OLD-C-1','OLD-T-1','e2e: old condition link')" >/dev/null
  old_admin grant codex:e2e-relink old defect task --note "e2e re-link creator"
  for spec in 'defect_task OLD-D-0001 OLD-T-1' 'same_site OLD-D-0001 OLD-D-0002' 'condition_task OLD-C-1 OLD-T-1'; do
    read -r kind a b <<<"$spec"
    login_sql ledger_author "SELECT ledger.link(gen_random_uuid(),'claude:e2e-seed','$kind','$a','$b','e2e: remove original link',true);
      SELECT ledger.link(gen_random_uuid(),'codex:e2e-relink','$kind','$a','$b','e2e: replacement link creator')" >/dev/null
  done
  # Later duplicated LINK history must not replace the creator of the current incarnation.
  pgsu -d "$DB" -c "INSERT INTO ledger.history(author_id,actor_assurance,entity,entity_id,op,reason,request_id)
    VALUES('claude:e2e-seed','DECLARED','defect_task','OLD-D-0001|OLD-T-1','LINK','e2e: duplicate historical link',gen_random_uuid())" >/dev/null
  # The same target text is valid in both tables; kind-qualified lifecycle evidence must remain independent.
  login_sql ledger_author "SELECT ledger.save(gen_random_uuid(),'claude:e2e-seed','task','OLD-D-0001',0,
    '{\"project_id\":\"old\",\"title\":\"Task alias\",\"purpose\":\"same target text\",\"success_condition\":\"kind separated\"}', 'e2e: task id aliases a defect')" >/dev/null
  for condition in OLD-C-2 OLD-C-3; do
    login_sql ledger_author "SELECT ledger.save(gen_random_uuid(),'claude:e2e-seed','condition','$condition',0,
      jsonb_build_object('project_id','old','release','old','code','$condition','condition_text','kind boundary'), 'e2e: condition kind provenance')" >/dev/null
  done
  for condition in OLD-C-1 OLD-C-2; do
    for kind in condition_task condition_defect; do
      login_sql ledger_author "SELECT ledger.link(gen_random_uuid(),'claude:e2e-seed','$kind','$condition','OLD-D-0001','e2e: aliased target link')" >/dev/null
    done
    kind=condition_defect; [ "$condition" != OLD-C-2 ] || kind=condition_task
    login_sql ledger_author "SELECT ledger.link(gen_random_uuid(),'claude:e2e-seed','$kind','$condition','OLD-D-0001','e2e: remove one alias kind',true);
      SELECT ledger.link(gen_random_uuid(),'codex:e2e-relink','$kind','$condition','OLD-D-0001','e2e: recreate one alias kind')" >/dev/null
  done
  login_sql ledger_author "SELECT ledger.link(gen_random_uuid(),'claude:e2e-seed','condition_task','OLD-C-3','OLD-T-1','e2e: initially typed link')" >/dev/null
  pgsu -d "$DB" -c "INSERT INTO ledger.history(author_id,actor_assurance,entity,entity_id,op,reason,request_id)
    VALUES('claude:e2e-seed','DECLARED','condition_link','OLD-C-3|OLD-T-1','LINK','e2e: untyped legacy link evidence',gen_random_uuid())" >/dev/null
  pgsu -d "$DB" -c "INSERT INTO ledger.task(id,project_id,title,purpose,success_condition,state,created_at,updated_at)
    VALUES('OLD-T-UNKNOWN','old','No history','','','PLANNED',now(),now())" >/dev/null
  pgsu -d "$DB" -c "INSERT INTO ledger.history(at,author_id,actor_assurance,entity,entity_id,op,reason,request_id,legacy_ref)
    VALUES('2000-01-01','legacy:earlier-note','LEGACY_UNKNOWN','task','OLD-T-1','LEGACY_NOTE','e2e: earlier note is not the creator',gen_random_uuid(),'e2e:legacy-earlier-note')" >/dev/null
  before=$(snapshot)
  source "$REPO/tests/upgrade-atomicity.sh"
  install_rollback_cases
  concurrent_upgrade rollback
  categories() { pgsu -d "$DB" -tAc "select string_agg(code || '|' || name || '|' || definition, ',' order by code) from ledger.category"; }
  cats_before=$(categories)
  echo "== concurrent upgrade commits constraints and API together"
  concurrent_upgrade commit
  echo "== reapply the unmodified candidate after the gated upgrade"
  "$REPO/deploy/install.sh" --db "$DB" --pg-port "$PG_PORT" --mcp-port "$MCP_PORT" --name "$NAME"
  pass_if 'upgrade keeps every existing row' "$(snapshot)" "$before"
  pass_if 'upgrade records step defect_case' "$(pgsu -d "$DB" -tAc "select count(*) from ledger._migration where step = 'defect_case'")" 1
  pass_if 'upgrade creates an empty defect_case' "$(pgsu -d "$DB" -tAc "select count(*) from ledger.defect_case")" 0
  pass_if 'upgrade records step common_code' "$(pgsu -d "$DB" -tAc "select count(*) from ledger._migration where step = 'common_code'")" 1
  pass_if 'upgrade moves the categories into common_code unchanged' "$(categories)" "$cats_before"
  pass_if 'known categories have English translations' "$(pgsu -d "$DB" -tAc "select count(*) from ledger.common_code where code_group='DEFECT_CATEGORY' and code <> 'OLDBUG' and name_en <> name_ko and description_en <> description_ko")" 14
  pass_if 'CON translation preserves concurrency and atomicity' "$(pgsu -d "$DB" -tAc "select name_en from ledger.common_code where code_group='DEFECT_CATEGORY' and code='CON'")" 'Concurrency and atomicity'
  pass_if 'EXC translation preserves error cause suppression or substitution' "$(pgsu -d "$DB" -tAc "select name_en from ledger.common_code where code_group='DEFECT_CATEGORY' and code='EXC'")" 'Error cause suppression or substitution'
  pass_if 'unknown categories use the original text as English fallback' "$(pgsu -d "$DB" -tAc "select name_en=name_ko and description_en=description_ko from ledger.common_code where code_group='DEFECT_CATEGORY' and code='OLDBUG'")" t
  pass_if 'entity creators backfill from CREATE or IMPORT' "$(pgsu -d "$DB" -tAc "select count(*) from (select created_by from ledger.defect union all select created_by from ledger.task where id in ('OLD-T-1','OLD-D-0001') union all select created_by from ledger.condition) x where created_by='claude:e2e-seed'")" 8
  pass_if 're-linked rows belong to the current incarnation creator' "$(pgsu -d "$DB" -tAc "select count(*) from (select created_by from ledger.defect_task union all select created_by from ledger.defect_relation union all select created_by from ledger.condition_link) x where created_by='codex:e2e-relink'")" 5
  pass_if 'kind aliases keep the other live incarnation creator' "$(pgsu -d "$DB" -tAc "select count(*) from ledger.condition_link where created_by='claude:e2e-seed' and ((condition_id='OLD-C-1' and task_id='OLD-D-0001') or (condition_id='OLD-C-2' and defect_id='OLD-D-0001'))")" 2
  pass_if 'untyped condition lifecycle does not invent a creator' "$(pgsu -d "$DB" -tAc "select created_by from ledger.condition_link where condition_id='OLD-C-3' and task_id='OLD-T-1'")" unknown:legacy
  pass_if 'missing legacy history is marked unknown' "$(pgsu -d "$DB" -tAc "select created_by from ledger.task where id='OLD-T-UNKNOWN'")" unknown:legacy
  pass_if 'an unrated defect keeps UNRATED until it is written' "$(pgsu -d "$DB" -tAc "select severity from ledger.defect where id = 'OLD-D-0003'")" UNRATED
  out=$(login_sql ledger_author "SELECT ledger.save(gen_random_uuid(), 'claude:e2e-seed', 'defect', 'OLD-D-0003', 1, jsonb_build_object('title', 'unrated, edited'),
    'e2e: edit without rating')" 2>&1) && echo "FAIL  an edit that leaves UNRATED succeeded: $out" && fail=1
  grep -q 'CODE_INVALID: defect.severity = UNRATED' <<<"$out" && echo "PASS  an edit that leaves UNRATED is refused" || { echo "FAIL  wrong error: $out"; fail=1; }
  pass_if 'the same edit with a rating is accepted' "$(login_sql ledger_author "SELECT ledger.save(gen_random_uuid(), 'claude:e2e-seed', 'defect', 'OLD-D-0003', 1,
    jsonb_build_object('title', 'unrated, edited', 'severity', 'P3'), 'e2e: edit with a rating')->>'version'")" 2
  pass_if 'a defect merged before the upgrade shows as a case of its target' \
    "$(login_sql ledger_reader "select string_agg((c->>'source') || ':' || (c->>'defect') || ':' || (c->>'observation'), ',')
       from ledger.v_defect, jsonb_array_elements(cases) c where id = 'OLD-D-0001'")" \
    'merged_behavior:OLD-D-0002:old merged behaviour'
fi

if [ -z "$UPGRADE_FROM" ]; then
  TOKEN=$(sed -n 's/^DBHUB_AUTH_TOKEN=//p' "/etc/$NAME/env")
  DBHUB_AUTH_TOKEN=$TOKEN node --input-type=module - "$REPO" "http://127.0.0.1:$MCP_PORT/mcp" <<'JS'
const { connect } = await import(`${process.argv[2]}/bin/mcp-call.mjs`);
const mcp = await connect(process.argv[3],process.env.DBHUB_AUTH_TOKEN);
const save = (await mcp.toolDefinitions()).find(t => t.name === "ledger_save");
if (!save.description.includes("(없음: ledger-admin category-add로 추가)")) throw new Error("empty category placeholder missing");
console.log("PASS  empty-category bootstrap is usable through tools/list");
JS
fi

echo "== owner setup"
admin project-add "$PROJECT" "$CODE" "End-to-end test project"
admin category-add BUG "결함" "명세와 다른 동작" --name-en 'Bug "quoted"' --description-en $'Behaviour differs\nwith "quotes" and \\path; Use @CODES:DEFECT_CATEGORY@ literally'
admin code-set RELATION_KIND test_relation "시험 관계" "동적 관계 코드" --name-en "Test relation" --description-en "Dynamically rendered relation" --sort 6
admin code-set DEFECT_CATEGORY RETIRED "이전 분류" "비활성" --inactive
admin grant claude:e2e-author "$PROJECT" defect task fix verify --note "e2e"
admin grant codex:e2e-reviewer "$PROJECT" verify --note "e2e"

TOKEN=$(sed -n 's/^DBHUB_AUTH_TOKEN=//p' "/etc/$NAME/env")
URL=http://127.0.0.1:$MCP_PORT/mcp
echo "== Claude hook contract (synthetic host input)"
node --test "$REPO/tests/claude-hook.mjs" || fail=1
node "$REPO/tests/host-attestation.mjs" "$REPO" || fail=1

echo "== MCP scenario"
DBHUB_AUTH_TOKEN=$TOKEN node "$REPO/tests/e2e.mjs" "$URL" "$PROJECT" "$CODE" || fail=1

echo "== race: a case recorded while its defect is being merged"
# A gate session holds an advisory lock. The merge takes the defect row lock, then waits at the gate. The case starts
# only once the merge waits there, and the gate is released only once the case waits for the row lock.
until_true() {  # <sql>: poll until it returns t; the 30 s bound only turns a hang into a failure
  local i; for i in $(seq 300); do [ "$(pgsu -d "$DB" -tAc "$1")" = t ] && return 0; sleep 0.1; done
  echo "FAIL  timed out waiting for: $1"; return 1
}
D5=$(login_sql ledger_author "SELECT ledger.save(gen_random_uuid(), 'claude:e2e-author', 'defect', NULL, 0, jsonb_build_object('project_id', '$PROJECT',
  'title', 'merge race', 'category', 'BUG', 'severity', 'P3', 'behavior', 'merge race behaviour', 'root_cause', 'n/a'), 'e2e: defect for the merge race')->>'id'")
pgsu -d "$DB" -tAc "select pg_advisory_lock(4242), pg_sleep(300)" >/dev/null 2>&1 & gate=$!
until_true "select exists (select 1 from pg_locks where locktype = 'advisory' and objid = 4242 and granted)"
PGAPPNAME=e2e-merge login_sql ledger_author "BEGIN; SELECT ledger.transition(gen_random_uuid(), 'claude:e2e-author', 'defect', '$D5', 1, 'MERGED',
  'e2e: merge racing a new case', NULL, NULL, '$CODE-D-0002'); SELECT pg_advisory_xact_lock(4242); COMMIT;" >/dev/null 2>&1 & merge=$!
until_true "select exists (select 1 from pg_stat_activity where application_name = 'e2e-merge' and wait_event_type = 'Lock')"
case_out=$(mktemp)
PGAPPNAME=e2e-case login_sql ledger_author "SELECT ledger.record_case(gen_random_uuid(), 'claude:e2e-author', '$D5', 'seen while the merge ran',
  'e2e: case racing a merge')" >"$case_out" 2>&1 & racer=$!
until_true "select exists (select 1 from pg_stat_activity where application_name = 'e2e-case' and wait_event_type = 'Lock')" || cat "$case_out"
pgsu -d "$DB" -tAc "select pg_terminate_backend(pid) from pg_locks where locktype = 'advisory' and objid = 4242 and granted" >/dev/null
wait "$gate" || true
if wait "$merge"; then echo "PASS  the merge commits"; else echo "FAIL  the merge failed"; fail=1; fi
if wait "$racer"; then echo "FAIL  a case was recorded on a defect merged meanwhile: $(cat "$case_out")"; fail=1
elif grep -q "MERGED_DEFECT: $D5 was merged into $CODE-D-0002" "$case_out"; then echo "PASS  the waiting case is refused with the merge target"
else echo "FAIL  wrong error: $(cat "$case_out")"; fail=1; fi
rm -f "$case_out"
pass_if 'no case stays on the merged defect' "$(pgsu -d "$DB" -tAc "select count(*) from ledger.defect_case where defect_id = '$D5'" 2>&1)" 0

echo "== database-level checks"
expect_error() {  # <name> <login> <needle> <sql>
  local out
  if out=$(login_sql "$2" "$4" 2>&1); then
    echo "FAIL  $1 (succeeded: $out)"; fail=1
  elif grep -q "$3" <<<"$out"; then echo "PASS  $1"
  else echo "FAIL  $1 (wrong error: $out)"; fail=1; fi
}
expect_error 'author login cannot verify even with a verify grant' ledger_author VERIFY_NEEDS_REVIEWER_LOGIN \
  "select ledger.transition(gen_random_uuid(), 'claude:e2e-author', 'defect', '$CODE-D-0002', 1, 'VERIFIED', 'e2e: direct verify attempt', 'https://ci.example/run/1')"
expect_error 'author login cannot write tables directly' ledger_author 'permission denied' \
  "update ledger.defect set title = 'x' where id = '$CODE-D-0002'"
expect_error 'author login cannot add cases directly' ledger_author 'permission denied' \
  "insert into ledger.defect_case (defect_id, observation, recorded_by, recorded_at) values ('$CODE-D-0002', 'x', 'claude:e2e-author', now())"
expect_error 'history is append-only' ledger_author 'permission denied' "delete from ledger.history"
expect_error 'author login cannot grant' ledger_author 'permission denied' "select ledger.grant_session('claude:x', '$PROJECT', 'defect')"
expect_error 'reader login is read-only' ledger_reader 'read-only transaction' "insert into ledger.project (id,code,title) values ('x', 'X', 'x')"
if pgsu -d "$DB" -c "delete from ledger.history" >/dev/null 2>&1; then echo "FAIL  history refuses delete even for the superuser"; fail=1
else echo "PASS  history refuses delete even for the superuser"; fi

echo "== preserved constraints and immutable provenance"
for spec in 'defect status' 'task state' 'condition state' 'defect_fix ci_conclusion' 'session_grant scope'; do
  read -r table column <<<"$spec"
  pass_if "fixed CHECK retained: $table.$column" "$(pgsu -d "$DB" -tAc "select exists(select 1 from pg_constraint c join pg_attribute a on a.attrelid=c.conrelid and a.attnum=any(c.conkey) where c.conrelid='ledger.$table'::regclass and c.contype='c' and c.conkey=ARRAY[a.attnum] and a.attname='$column')")" t
done
pass_if 'only three code use mappings exist' "$(pgsu -d "$DB" -tAc "select count(*) from ledger.common_code_use")" 3
pass_if 'owner rows are automatically stamped' "$(pgsu -d "$DB" -tAc "select created_by from ledger.project where id='$PROJECT'")" user:owner
pgsu -d "$DB" -c "UPDATE ledger.defect SET created_by='model:forged' WHERE id='$CODE-D-0002'" >/dev/null
pass_if 'direct UPDATE cannot change created_by' "$(pgsu -d "$DB" -tAc "select created_by from ledger.defect where id='$CODE-D-0002'")" claude:e2e-author
expect_error 'inactive category is refused at the DB boundary' ledger_author 'CODE_INVALID: defect.category = RETIRED' \
  "select ledger.save(gen_random_uuid(),'claude:e2e-author','defect','$CODE-D-0002',1,jsonb_build_object('category','RETIRED'),'e2e: retired category')"
expect_error 'unknown relation names valid DB codes' ledger_author 'link: unknown kind related' \
  "select ledger.link(gen_random_uuid(),'claude:e2e-author','related','$CODE-D-0001','$CODE-D-0002','e2e: unknown relation')"
pass_if 'all eight created_by columns are NOT NULL' "$(pgsu -d "$DB" -tAc "select count(*) from information_schema.columns where table_schema='ledger' and column_name='created_by' and is_nullable='NO'")" 8
before_reapply=$(snapshot)
creators=$(pgsu -d "$DB" -tAc "select string_agg(id||':'||created_by,',' order by id) from ledger.defect")

echo "== install again (re-apply keeps data, token and passwords)"
cases=$(pgsu -d "$DB" -tAc "select count(*) from ledger.defect_case" 2>&1 || true)
"$REPO/deploy/install.sh" --db "$DB" --pg-port "$PG_PORT" --mcp-port "$MCP_PORT" --name "$NAME"
pass_if 'reapply preserves every semantic row' "$(snapshot)" "$before_reapply"
pass_if 'reapply preserves creators' "$(pgsu -d "$DB" -tAc "select string_agg(id||':'||created_by,',' order by id) from ledger.defect")" "$creators"
pass_if 'created_by migration is recorded once' "$(pgsu -d "$DB" -tAc "select count(*) from ledger._migration where step='created_by'")" 1
[ "$(sed -n 's/^DBHUB_AUTH_TOKEN=//p' "/etc/$NAME/env")" = "$TOKEN" ] && echo "PASS  token kept" || { echo "FAIL  token changed"; fail=1; }
n=$(pgsu -d "$DB" -tAc "select count(*) from ledger.defect where project_id = '$PROJECT'")
[ "$n" = 5 ] && echo "PASS  data kept ($n defects)" || { echo "FAIL  defects after re-install: $n"; fail=1; }
pass_if 'cases kept by the re-install' "$(pgsu -d "$DB" -tAc "select count(*) from ledger.defect_case" 2>&1 || true)" "$cases"
[ "$cases" -gt 0 ] 2>/dev/null && echo "PASS  cases recorded ($cases)" || { echo "FAIL  no cases recorded ($cases)"; fail=1; }
ls /var/backups/$NAME/ledger-*.dump >/dev/null && echo "PASS  backup taken before re-apply" || { echo "FAIL  no backup"; fail=1; }

[ "$fail" = 0 ] && echo "E2E PASSED" || { echo "E2E FAILED"; exit 1; }
