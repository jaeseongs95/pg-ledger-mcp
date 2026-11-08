#!/usr/bin/env bash
# one-click-install.sh : One-click zero-config installer for pg-ledger-mcp (PostgreSQL schema + DBHub MCP + Admin Web Console).
# Run as root on the database host. Idempotent: safe to re-run without losing existing data, passwords, or tokens.
#
#   sudo deploy/one-click-install.sh [--db ledger] [--pg-port 5432] [--mcp-port 8765] [--admin-port 8766]
#                                    [--name pg-ledger-mcp] [--allowed-hosts mcp.example.com]
#                                    [--project <id> <CODE> <title>] [--skip-admin] [--reset-passwords]
set -euo pipefail
umask 077

REPO=$(cd "$(dirname "$0")/.." && pwd)
NAME=pg-ledger-mcp
DB=ledger
PG_PORT=5432
MCP_PORT=8765
ADMIN_PORT=8766
ALLOWED_HOSTS=
SKIP_ADMIN=0
RESET_PASSWORDS=0
INIT_PROJECT_ID=
INIT_PROJECT_CODE=
INIT_PROJECT_TITLE=

die() { echo "one-click-install.sh: $*" >&2; exit 1; }
usage() { sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --name) NAME=${2:?}; shift 2 ;;
    --db) DB=${2:?}; shift 2 ;;
    --pg-port) PG_PORT=${2:?}; shift 2 ;;
    --mcp-port) MCP_PORT=${2:?}; shift 2 ;;
    --admin-port) ADMIN_PORT=${2:?}; shift 2 ;;
    --allowed-hosts) ALLOWED_HOSTS=${2:?}; shift 2 ;;
    --project)
      [ $# -ge 4 ] || die "--project requires <id> <CODE> <title>"
      INIT_PROJECT_ID=$2; INIT_PROJECT_CODE=$3; INIT_PROJECT_TITLE=$4; shift 4 ;;
    --skip-admin) SKIP_ADMIN=1; shift ;;
    --reset-passwords) RESET_PASSWORDS=1; shift ;;
    -h|--help) usage ;;
    *) echo "unknown argument: $1" >&2; usage 2 ;;
  esac
done

[ "$(id -u)" = 0 ] || die "run as root (sudo deploy/one-click-install.sh)"
[[ "$NAME" =~ ^[a-z][a-z0-9-]{0,40}$ ]] || die "--name must match ^[a-z][a-z0-9-]{0,40}$"
[[ "$DB" =~ ^[a-zA-Z_][a-zA-Z0-9_]{0,62}$ ]] || die "--db must be a valid PostgreSQL identifier"
[[ "$PG_PORT" =~ ^[0-9]+$ && "$MCP_PORT" =~ ^[0-9]+$ && "$ADMIN_PORT" =~ ^[0-9]+$ ]] || die "ports must be numbers"

echo "== [1/4] Checking host prerequisites (PostgreSQL >= 15, Node.js >= 22.5, systemd)"
for c in psql pg_dump runuser node npm curl systemctl; do
  command -v "$c" >/dev/null || die "missing required command: $c"
done

psql_major=$(psql --version | sed -E 's/[^0-9]*([0-9]+).*/\1/')
[ "$psql_major" -ge 15 ] || die "psql 15 or newer is required (found $psql_major)"
node -e 'const [a,b]=process.versions.node.split(".").map(Number); process.exit(a>22||(a===22&&b>=5)?0:1)' \
  || die "Node.js >= 22.5 is required (found $(node -v))"

echo "== [2/4] Ensuring PostgreSQL database '$DB' exists on port $PG_PORT"
runuser -u postgres -- psql -X -q -v ON_ERROR_STOP=1 -p "$PG_PORT" -d postgres -tAc "SELECT 1" >/dev/null \
  || die "cannot connect to PostgreSQL on port $PG_PORT as postgres"

db_exists=$(runuser -u postgres -- psql -X -q -v ON_ERROR_STOP=1 -p "$PG_PORT" -d postgres -v db="$DB" -tAc \
  "SELECT 1 FROM pg_database WHERE datname = :'db'")
if [ "$db_exists" != "1" ]; then
  echo "creating database '$DB' (UTF8)"
  runuser -u postgres -- createdb -p "$PG_PORT" -E UTF8 "$DB"
else
  echo "database '$DB' already exists (preserving existing data)"
fi

echo "== [3/4] Running core installer (schema, roles, DBHub MCP, and Admin Web Console)"
install_args=(--db "$DB" --pg-port "$PG_PORT" --mcp-port "$MCP_PORT" --admin-port "$ADMIN_PORT" --name "$NAME")
[ -z "$ALLOWED_HOSTS" ] || install_args+=(--allowed-hosts "$ALLOWED_HOSTS")
[ "$SKIP_ADMIN" = 0 ] || install_args+=(--skip-admin)
[ "$RESET_PASSWORDS" = 0 ] || install_args+=(--reset-passwords)

"$REPO/deploy/install.sh" "${install_args[@]}"

if [ -n "$INIT_PROJECT_ID" ]; then
  echo "== [3b/4] Ensuring initial project '$INIT_PROJECT_ID' ($INIT_PROJECT_CODE)"
  exists_proj=$(runuser -u postgres -- psql -X -q -v ON_ERROR_STOP=1 -p "$PG_PORT" -d "$DB" -v id="$INIT_PROJECT_ID" -tAc \
    "SELECT 1 FROM ledger.project WHERE id = :'id'")
  if [ "$exists_proj" != "1" ]; then
    "$REPO/bin/ledger-admin" --name "$NAME" project-add "$INIT_PROJECT_ID" "$INIT_PROJECT_CODE" "$INIT_PROJECT_TITLE" >/dev/null
    echo "registered project: $INIT_PROJECT_ID ($INIT_PROJECT_CODE)"
  else
    echo "project '$INIT_PROJECT_ID' already exists"
  fi
fi

echo "== [4/4] Verifying service readiness"
curl -fsS "http://127.0.0.1:$MCP_PORT/healthz" >/dev/null \
  || die "MCP endpoint http://127.0.0.1:$MCP_PORT/healthz failed health check"
if [ "$SKIP_ADMIN" = 0 ]; then
  curl -fsS "http://127.0.0.1:$ADMIN_PORT/healthz" >/dev/null \
    || die "Admin web console http://127.0.0.1:$ADMIN_PORT/healthz failed health check"
fi

ENV_FILE=/etc/$NAME/env
cat <<EOF
======================================================================
pg-ledger-mcp one-click installation completed!
======================================================================
  Instance Name : $NAME
  Database      : $DB (127.0.0.1:$PG_PORT)
  Credentials   : $ENV_FILE (mode 0600, root only)

  [1] MCP Server (DBHub for AI Agents)
      Service   : $NAME.service
      URL       : http://127.0.0.1:$MCP_PORT/mcp
      Auth Key  : DBHUB_AUTH_TOKEN (in $ENV_FILE)

  [2] Admin Web Console (Owner Control + Read-Only Explorer)
      Service   : $( [ "$SKIP_ADMIN" = 0 ] && echo "$NAME-admin.service" || echo "skipped (--skip-admin)" )
      URL       : http://127.0.0.1:$ADMIN_PORT
      Auth Key  : PGLEDGER_ADMIN_TOKEN (in $ENV_FILE; separated from agent token)
      SSH Tunnel: ssh -L $ADMIN_PORT:127.0.0.1:$ADMIN_PORT <user>@<host>
======================================================================
EOF
