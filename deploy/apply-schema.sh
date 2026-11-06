#!/usr/bin/env bash
# One transaction for the complete schema upgrade, including replacement constraints and API triggers.
set -euo pipefail
REPO=$(cd "$(dirname "$0")/.." && pwd)
DB= PG_PORT=5432 FRESH=0
while [ $# -gt 0 ]; do
  case "$1" in
    --db) DB=${2:?}; shift 2 ;;
    --pg-port) PG_PORT=${2:?}; shift 2 ;;
    --fresh) FRESH=1; shift ;;
    *) echo "usage: apply-schema.sh --db <db> [--pg-port <port>] [--fresh]" >&2; exit 2 ;;
  esac
done
[ -n "$DB" ] && [ "$(id -u)" = 0 ] || { echo "apply-schema.sh: database and root required" >&2; exit 2; }
{
  cat <<'SQL'
\set ON_ERROR_STOP on
\set ledger_install_transaction true
BEGIN;
SELECT pg_advisory_xact_lock(hashtext('pg-ledger-mcp:schema'));
DO $lock$
DECLARE tables text;
BEGIN
  SELECT string_agg(format('%I.%I',schemaname,tablename),', ' ORDER BY tablename) INTO tables
    FROM pg_tables WHERE schemaname='ledger';
  IF tables IS NOT NULL THEN EXECUTE 'LOCK TABLE ' || tables || ' IN ACCESS EXCLUSIVE MODE'; END IF;
END $lock$;
SQL
  if [ "$FRESH" = 1 ]; then cat "$REPO/sql/10_tables.sql" || exit; fi
  for file in 15_defect_case 16_common_code 17_created_by 20_api; do cat "$REPO/sql/$file.sql" || exit; done
  printf '\nCOMMIT;\n'
} | PGOPTIONS="-c client_min_messages=warning" runuser -u postgres -- psql -X -q -v ON_ERROR_STOP=1 -p "$PG_PORT" -d "$DB"
