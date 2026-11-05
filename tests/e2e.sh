#!/usr/bin/env bash
# e2e.sh : install into a throwaway database, drive it through MCP, re-run the installer, clean up.
#   sudo tests/e2e.sh --pg-port <port of a DISPOSABLE cluster> --disposable-cluster [--mcp-port 18765] [--keep]
# The installer resets the passwords of the cluster-wide ledger_* logins, so never point this at a cluster
# that serves a real ledger. Example for Debian/Ubuntu: pg_createcluster 16 e2e --port 5544 --start
set -euo pipefail
REPO=$(cd "$(dirname "$0")/.." && pwd)
PG_PORT= MCP_PORT=18765 KEEP=0 DISPOSABLE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --pg-port) PG_PORT=${2:?}; shift 2 ;;
    --mcp-port) MCP_PORT=${2:?}; shift 2 ;;
    --keep) KEEP=1; shift ;;
    --disposable-cluster) DISPOSABLE=1; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[ -n "$PG_PORT" ] && [ "$DISPOSABLE" = 1 ] || { sed -n '2,5p' "$0"; exit 2; }
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
}
trap cleanup EXIT

pgsu -d postgres -c "CREATE DATABASE $DB"
echo "== install (fresh)"
"$REPO/deploy/install.sh" --db "$DB" --pg-port "$PG_PORT" --mcp-port "$MCP_PORT" --name "$NAME" --reset-passwords

echo "== owner setup"
admin() { "$REPO/bin/ledger-admin" --name "$NAME" "$@" >/dev/null; }
admin project-add "$PROJECT" "$CODE" "End-to-end test project"
admin category-add BUG "Bug" "Behaviour differs from the specification"
admin grant claude:e2e-author "$PROJECT" defect task fix verify --note "e2e"
admin grant codex:e2e-reviewer "$PROJECT" verify --note "e2e"

TOKEN=$(sed -n 's/^DBHUB_AUTH_TOKEN=//p' "/etc/$NAME/env")
URL=http://127.0.0.1:$MCP_PORT/mcp
echo "== MCP scenario"
DBHUB_AUTH_TOKEN=$TOKEN node "$REPO/tests/e2e.mjs" "$URL" "$PROJECT" "$CODE"

echo "== database-level checks"
fail=0
expect_error() {  # <name> <login> <needle> <sql>
  local pw out
  pw=$(sed -n "s/^PGLEDGER_$(echo "$2" | sed 's/ledger_//' | tr a-z A-Z)_PASSWORD=//p" "/etc/$NAME/env")
  if out=$(PGPASSWORD=$pw psql -X -q -h 127.0.0.1 -p "$PG_PORT" -U "$2" -d "$DB" -tAc "$4" 2>&1); then
    echo "FAIL  $1 (succeeded: $out)"; fail=1
  elif grep -q "$3" <<<"$out"; then echo "PASS  $1"
  else echo "FAIL  $1 (wrong error: $out)"; fail=1; fi
}
expect_error 'author login cannot verify even with a verify grant' ledger_author VERIFY_NEEDS_REVIEWER_LOGIN \
  "select ledger.transition(gen_random_uuid(), 'claude:e2e-author', 'defect', '$CODE-D-0002', 1, 'VERIFIED', 'e2e: direct verify attempt', 'https://ci.example/run/1')"
expect_error 'author login cannot write tables directly' ledger_author 'permission denied' \
  "update ledger.defect set title = 'x' where id = '$CODE-D-0002'"
expect_error 'history is append-only' ledger_author 'permission denied' "delete from ledger.history"
expect_error 'author login cannot grant' ledger_author 'permission denied' "select ledger.grant_session('claude:x', '$PROJECT', 'defect')"
expect_error 'reader login is read-only' ledger_reader 'read-only transaction' "insert into ledger.project values ('x', 'X', 'x')"
if pgsu -d "$DB" -c "delete from ledger.history" >/dev/null 2>&1; then echo "FAIL  history refuses delete even for the superuser"; fail=1
else echo "PASS  history refuses delete even for the superuser"; fi

echo "== install again (re-apply keeps data, token and passwords)"
"$REPO/deploy/install.sh" --db "$DB" --pg-port "$PG_PORT" --mcp-port "$MCP_PORT" --name "$NAME"
[ "$(sed -n 's/^DBHUB_AUTH_TOKEN=//p' "/etc/$NAME/env")" = "$TOKEN" ] && echo "PASS  token kept" || { echo "FAIL  token changed"; fail=1; }
n=$(pgsu -d "$DB" -tAc "select count(*) from ledger.defect")
[ "$n" = 2 ] && echo "PASS  data kept ($n defects)" || { echo "FAIL  defects after re-install: $n"; fail=1; }
ls /var/backups/$NAME/ledger-*.dump >/dev/null && echo "PASS  backup taken before re-apply" || { echo "FAIL  no backup"; fail=1; }

[ "$fail" = 0 ] && echo "E2E PASSED" || { echo "E2E FAILED"; exit 1; }
