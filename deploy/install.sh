#!/usr/bin/env bash
# install.sh : install (or re-apply) the ledger schema in a PostgreSQL database and serve it as one MCP endpoint (DBHub).
# Run as root on the database host. Safe to re-run: tables are created once, functions/views/grants are re-applied,
# passwords and the bearer token are kept, the service is restarted.
#
#   sudo deploy/install.sh --db ledger [--pg-port 5432] [--mcp-port 8765] [--allowed-hosts mcp.example.com] [--name pg-ledger-mcp]
#                          [--schema-only] [--reset-passwords]
#
# The roles ledger_owner/author/reviewer/reader are cluster-wide, so one PostgreSQL cluster serves one ledger instance.
set -euo pipefail
umask 077

REPO=$(cd "$(dirname "$0")/.." && pwd)
DBHUB_VERSION=1.5.1
NAME=pg-ledger-mcp
DB=
PG_PORT=5432
MCP_PORT=8765
ALLOWED_HOSTS=
SCHEMA_ONLY=0
RESET_PASSWORDS=0
PREFIX=/opt/pg-ledger-mcp
SVC_USER=pg-ledger-mcp

die() { echo "install.sh: $*" >&2; exit 1; }
usage() { sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }
while [ $# -gt 0 ]; do
  case "$1" in
    --db) DB=${2:?}; shift 2 ;;
    --pg-port) PG_PORT=${2:?}; shift 2 ;;
    --mcp-port) MCP_PORT=${2:?}; shift 2 ;;
    --allowed-hosts) ALLOWED_HOSTS=${2:?}; shift 2 ;;
    --name) NAME=${2:?}; shift 2 ;;
    --schema-only) SCHEMA_ONLY=1; shift ;;
    --reset-passwords) RESET_PASSWORDS=1; shift ;;
    -h|--help) usage ;;
    *) echo "unknown argument: $1" >&2; usage 2 ;;
  esac
done
[ -n "$DB" ] || usage 2
[[ "$NAME" =~ ^[a-z][a-z0-9-]{0,40}$ ]] || die "--name must match ^[a-z][a-z0-9-]{0,40}$"
[[ "$PG_PORT" =~ ^[0-9]+$ && "$MCP_PORT" =~ ^[0-9]+$ ]] || die "ports must be numbers"
[ "$(id -u)" = 0 ] || die "run as root"
ETC=/etc/$NAME
ENV_FILE=$ETC/env

for c in psql pg_dump runuser; do command -v "$c" >/dev/null || die "$c not found"; done
psql_major=$(psql --version | sed -E 's/[^0-9]*([0-9]+).*/\1/')
[ "$psql_major" -ge 15 ] || die "psql 15 or newer is needed (\\getenv), found $psql_major"

# psql as the postgres superuser; SQL comes on stdin so the files need not be readable by postgres
pg() { PGOPTIONS="-c client_min_messages=warning" runuser -u postgres -- psql -X -q -v ON_ERROR_STOP=1 -p "$PG_PORT" -d "$DB" "$@"; }
pg -tAc 'select 1' >/dev/null || die "cannot connect to database $DB on port $PG_PORT as postgres"

# ---------- schema ----------
# (ledger._migration is only referenced once it is known to exist: SQL naming a missing table fails at parse time)
state=$(pg -tAc "select case when to_regclass('ledger._migration') is not null then 'check'
  when exists (select 1 from pg_namespace where nspname = 'ledger') then 'foreign' else 'none' end")
if [ "$state" = check ]; then
  state=$(pg -tAc "select case when exists (select 1 from ledger._migration where step = 'schema_v2') then 'ready' else 'foreign' end")
fi
case "$state" in
  none)
    echo "creating schema ledger in $DB"
    pg < "$REPO/sql/10_tables.sql" ;;
  ready)
    backup_dir=/var/backups/$NAME; install -d -m 0700 "$backup_dir"
    backup=$backup_dir/ledger-$(date -u +%Y%m%dT%H%M%SZ).dump
    runuser -u postgres -- pg_dump -p "$PG_PORT" -d "$DB" -n ledger -Fc > "$backup"
    echo "schema ledger exists; backup of schema ledger: $backup" ;;
  foreign)
    die "schema ledger exists in $DB but was not created by this project (no ledger._migration step schema_v2); refusing to touch it" ;;
esac
# tables added after schema_v2: each file runs once per database (guarded by its ledger._migration step), existing rows stay
pg < "$REPO/sql/15_defect_case.sql"
pg < "$REPO/sql/16_common_code.sql"
pg < "$REPO/sql/20_api.sql"
echo "functions, views and grants applied"

# ---------- passwords (hex, kept in $ENV_FILE, mode 0600 root) ----------
install -d -m 0755 "$ETC"
envget() { [ -f "$ENV_FILE" ] && sed -n "s/^$1=//p" "$ENV_FILE" | tail -n 1 || true; }
rand_hex() { od -An -N"$1" -tx1 /dev/urandom | tr -d ' \n'; }

have_pw=$(pg -tAc "select count(*) from pg_authid where rolname in ('ledger_author','ledger_reviewer','ledger_reader') and rolpassword is not null")
if [ ! -f "$ENV_FILE" ] && [ "$have_pw" != 0 ] && [ "$RESET_PASSWORDS" = 0 ]; then
  die "the ledger logins already have passwords in this cluster but $ENV_FILE does not exist.
Another installation may use them. Re-run with --reset-passwords to set new ones (other clients of these logins stop working)."
fi
AUTHOR_PW=$(envget PGLEDGER_AUTHOR_PASSWORD); REVIEWER_PW=$(envget PGLEDGER_REVIEWER_PASSWORD); READER_PW=$(envget PGLEDGER_READER_PASSWORD)
TOKEN=$(envget DBHUB_AUTH_TOKEN)
if [ "$RESET_PASSWORDS" = 1 ]; then AUTHOR_PW=; REVIEWER_PW=; READER_PW=; fi
[ -n "$AUTHOR_PW" ] || AUTHOR_PW=$(rand_hex 24)
[ -n "$REVIEWER_PW" ] || REVIEWER_PW=$(rand_hex 24)
[ -n "$READER_PW" ] || READER_PW=$(rand_hex 24)
[ -n "$TOKEN" ] || TOKEN=$(rand_hex 32)
[ -n "$ALLOWED_HOSTS" ] || ALLOWED_HOSTS=$(envget DBHUB_ALLOWED_HOSTS)
# loopback always stays allowed: the smoke check below and local clients use it
case ",$ALLOWED_HOSTS," in *,127.0.0.1,*) ;; *) ALLOWED_HOSTS=${ALLOWED_HOSTS:+$ALLOWED_HOSTS,}localhost,127.0.0.1 ;; esac

# Passwords travel in the environment of psql (readable only by root and postgres), never on a command line,
# and the statements are not logged.
printf '%s\n' "SET log_statement = 'none';" \
  '\getenv pw_a PGLEDGER_A' '\getenv pw_r PGLEDGER_R' '\getenv pw_d PGLEDGER_D' \
  "ALTER ROLE ledger_author PASSWORD :'pw_a';" "ALTER ROLE ledger_reviewer PASSWORD :'pw_r';" "ALTER ROLE ledger_reader PASSWORD :'pw_d';" \
  | PGLEDGER_A=$AUTHOR_PW PGLEDGER_R=$REVIEWER_PW PGLEDGER_D=$READER_PW pg

tmp=$(mktemp "$ETC/.env.XXXXXX")
cat > "$tmp" <<EOF
# written by pg-ledger-mcp deploy/install.sh; read by systemd ($NAME.service) and bin/ledger-admin
PGLEDGER_DB=$DB
PGLEDGER_PGHOST=127.0.0.1
PGLEDGER_PGPORT=$PG_PORT
PGLEDGER_SSLMODE=disable
PGLEDGER_MCP_PORT=$MCP_PORT
PGLEDGER_AUTHOR_PASSWORD=$AUTHOR_PW
PGLEDGER_REVIEWER_PASSWORD=$REVIEWER_PW
PGLEDGER_READER_PASSWORD=$READER_PW
DBHUB_AUTH_TOKEN=$TOKEN
DBHUB_ALLOWED_HOSTS=$ALLOWED_HOSTS
EOF
chmod 0600 "$tmp"; mv -f "$tmp" "$ENV_FILE"
echo "passwords and bearer token: $ENV_FILE (root only)"

# each login must be able to log in over TCP with its password (pg_hba must allow 127.0.0.1 with scram-sha-256)
for pair in "ledger_author:$AUTHOR_PW" "ledger_reviewer:$REVIEWER_PW" "ledger_reader:$READER_PW"; do
  role=${pair%%:*}
  PGPASSWORD=${pair#*:} psql -X -q -h 127.0.0.1 -p "$PG_PORT" -U "$role" -d "$DB" -tAc "select ledger.whoami(null)->>'session_user'" >/dev/null \
    || die "login $role over 127.0.0.1:$PG_PORT failed; allow it in pg_hba.conf (host $DB $role 127.0.0.1/32 scram-sha-256)"
done
echo "logins ledger_author, ledger_reviewer, ledger_reader work over TCP"
[ "$SCHEMA_ONLY" = 1 ] && { echo "--schema-only: done"; exit 0; }

# ---------- DBHub ----------
for c in node npm curl systemctl; do command -v "$c" >/dev/null || die "$c not found"; done
NODE=$(command -v node)
node -e 'const [a,b]=process.versions.node.split(".").map(Number); process.exit(a>22||(a===22&&b>=5)?0:1)' \
  || die "DBHub $DBHUB_VERSION needs node >= 22.5 (found $(node -v))"
DBHUB_JS=$PREFIX/dbhub-$DBHUB_VERSION/node_modules/@bytebase/dbhub/dist/index.js
if [ ! -f "$DBHUB_JS" ]; then
  install -d -m 0755 "$PREFIX"
  ( umask 022; npm install --prefix "$PREFIX/dbhub-$DBHUB_VERSION" --omit=dev --no-audit --no-fund --loglevel=error "@bytebase/dbhub@$DBHUB_VERSION" )
fi
[ -f "$DBHUB_JS" ] || die "DBHub install failed: $DBHUB_JS missing"

id -u "$SVC_USER" >/dev/null 2>&1 || useradd --system --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin "$SVC_USER"
install -m 0644 "$REPO/mcp/dbhub.toml" "$ETC/dbhub.toml"
sed -e "s#@NAME@#$NAME#g" -e "s#@NODE@#$NODE#g" -e "s#@DBHUB_JS@#$DBHUB_JS#g" -e "s#@ETC@#$ETC#g" -e "s#@USER@#$SVC_USER#g" \
  "$REPO/deploy/pg-ledger-mcp.service" > "/etc/systemd/system/$NAME.service"
chmod 0644 "/etc/systemd/system/$NAME.service"
systemctl daemon-reload
systemctl enable "$NAME.service" >/dev/null
systemctl restart "$NAME.service"

# ---------- smoke check: health, then the tool list over MCP with the bearer token ----------
url=http://127.0.0.1:$MCP_PORT
deadline=$((SECONDS + 30))
until curl -fsS "$url/healthz" >/dev/null 2>&1; do
  [ $SECONDS -lt $deadline ] || { journalctl -u "$NAME" -n 40 --no-pager >&2; die "$NAME did not answer $url/healthz within 30 s"; }
  sleep 1
done
DBHUB_AUTH_TOKEN=$TOKEN "$NODE" "$REPO/bin/mcp-call.mjs" "$url/mcp" check
echo "$NAME is serving $url/mcp (allowed hosts: $ALLOWED_HOSTS). Clients need: Authorization: Bearer <DBHUB_AUTH_TOKEN from $ENV_FILE>"
