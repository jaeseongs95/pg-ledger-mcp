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
pgsu() { runuser -u postgres -- psql -X -q -v ON_ERROR_STOP=1 -p "$PG_PORT" "$@"; }
cleanup() {
  [ "$KEEP" = 1 ] && { echo "kept: database $DB, unit $NAME, /etc/$NAME"; return; }
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
# digest of every row of every ledger table, except install bookkeeping (_migration) and tables added later
snapshot() {
  pgsu -d "$DB" -tA <<'SQL'
SELECT string_agg(t.tablename || '=' || (xpath('/row/h/text()', query_to_xml(format(
         'SELECT md5(coalesce(string_agg(x::text, E''\n'' ORDER BY x::text), '''')) AS h FROM ledger.%I x', t.tablename), false, true, '')))[1]::text,
       ' ' ORDER BY t.tablename)
FROM pg_tables t WHERE t.schemaname = 'ledger' AND t.tablename NOT IN ('_migration', 'defect_case');
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
  admin project-add old OLD "Data written before the upgrade"
  admin category-add OLDBUG "Old bug" "Category written before the upgrade"
  admin grant claude:e2e-seed old defect --note "e2e seed"
  seed=
  for t in target merged; do
    seed+="SELECT ledger.save(gen_random_uuid(), 'claude:e2e-seed', 'defect', NULL, 0, jsonb_build_object('project_id', 'old', 'title', '$t',
      'category', 'OLDBUG', 'severity', 'P2', 'behavior', 'old $t behaviour', 'root_cause', 'old cause'), 'e2e: seed before the upgrade');"
  done
  seed+="SELECT ledger.transition(gen_random_uuid(), 'claude:e2e-seed', 'defect', 'OLD-D-0002', 1, 'MERGED', 'e2e: seed merge before the upgrade',
    NULL, NULL, 'OLD-D-0001');"
  login_sql ledger_author "$seed" >/dev/null
  pass_if 'old schema has no defect_case table' "$(pgsu -d "$DB" -tAc "select to_regclass('ledger.defect_case') is null")" t
  before=$(snapshot)
  echo "== upgrade: install this checkout over it"
  "$REPO/deploy/install.sh" --db "$DB" --pg-port "$PG_PORT" --mcp-port "$MCP_PORT" --name "$NAME"
  pass_if 'upgrade keeps every existing row' "$(snapshot)" "$before"
  pass_if 'upgrade records step defect_case' "$(pgsu -d "$DB" -tAc "select count(*) from ledger._migration where step = 'defect_case'")" 1
  pass_if 'upgrade creates an empty defect_case' "$(pgsu -d "$DB" -tAc "select count(*) from ledger.defect_case")" 0
  pass_if 'a defect merged before the upgrade shows as a case of its target' \
    "$(login_sql ledger_reader "select string_agg((c->>'source') || ':' || (c->>'defect') || ':' || (c->>'observation'), ',')
       from ledger.v_defect, jsonb_array_elements(cases) c where id = 'OLD-D-0001'")" \
    'merged_behavior:OLD-D-0002:old merged behaviour'
fi

echo "== owner setup"
admin project-add "$PROJECT" "$CODE" "End-to-end test project"
admin category-add BUG "Bug" "Behaviour differs from the specification"
admin grant claude:e2e-author "$PROJECT" defect task fix verify --note "e2e"
admin grant codex:e2e-reviewer "$PROJECT" verify --note "e2e"

TOKEN=$(sed -n 's/^DBHUB_AUTH_TOKEN=//p' "/etc/$NAME/env")
URL=http://127.0.0.1:$MCP_PORT/mcp
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
expect_error 'reader login is read-only' ledger_reader 'read-only transaction' "insert into ledger.project values ('x', 'X', 'x')"
if pgsu -d "$DB" -c "delete from ledger.history" >/dev/null 2>&1; then echo "FAIL  history refuses delete even for the superuser"; fail=1
else echo "PASS  history refuses delete even for the superuser"; fi

echo "== install again (re-apply keeps data, token and passwords)"
cases=$(pgsu -d "$DB" -tAc "select count(*) from ledger.defect_case" 2>&1 || true)
"$REPO/deploy/install.sh" --db "$DB" --pg-port "$PG_PORT" --mcp-port "$MCP_PORT" --name "$NAME"
[ "$(sed -n 's/^DBHUB_AUTH_TOKEN=//p' "/etc/$NAME/env")" = "$TOKEN" ] && echo "PASS  token kept" || { echo "FAIL  token changed"; fail=1; }
n=$(pgsu -d "$DB" -tAc "select count(*) from ledger.defect where project_id = '$PROJECT'")
[ "$n" = 5 ] && echo "PASS  data kept ($n defects)" || { echo "FAIL  defects after re-install: $n"; fail=1; }
pass_if 'cases kept by the re-install' "$(pgsu -d "$DB" -tAc "select count(*) from ledger.defect_case" 2>&1 || true)" "$cases"
[ "$cases" -gt 0 ] 2>/dev/null && echo "PASS  cases recorded ($cases)" || { echo "FAIL  no cases recorded ($cases)"; fail=1; }
ls /var/backups/$NAME/ledger-*.dump >/dev/null && echo "PASS  backup taken before re-apply" || { echo "FAIL  no backup"; fail=1; }

[ "$fail" = 0 ] && echo "E2E PASSED" || { echo "E2E FAILED"; exit 1; }
