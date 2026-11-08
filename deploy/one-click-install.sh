#!/usr/bin/env bash
# one-click-install.sh : One-click zero-config installer for pg-ledger-mcp (PostgreSQL schema + DBHub MCP + Admin Web Console + optional Custom Domain).
# Run as root on the database host. Works both from a local checkout and via `curl -fsSL .../one-click-install.sh | sudo bash -s -- ...`.
# Idempotent: safe to re-run without losing existing data, passwords, or tokens.
#
#   sudo deploy/one-click-install.sh [--db ledger] [--pg-port 5432] [--mcp-port 8765] [--admin-port 8865]
#                                    [--name pg-ledger-mcp] [--allowed-hosts mcp.example.com] [--admin-allowed-hosts admin.example.com]
#                                    [--domain jaeseong.dev] [--mcp-host ledger-mcp.jaeseong.dev] [--admin-host ledger-admin.jaeseong.dev]
#                                    [--domain-mode auto|origin-tls|cloudflare-api|cloudflared-config|nginx|allowlist-only] [--cf-tunnel-id <uuid>]
#                                    [--google-client-id <id>] [--google-client-secret <secret>] [--allowed-email <email>]
#                                    [--repo-dir /opt/pg-ledger-mcp/repo] [--repo-url https://github.com/jaeseongs95/pg-ledger-mcp.git] [--branch main]
#                                    [--project <id> <CODE> <title>] [--skip-admin] [--reset-passwords]
set -euo pipefail
umask 077

NAME=${PGLEDGER_NAME:-pg-ledger-mcp}
DB=${PGLEDGER_DB:-ledger}
PG_PORT=${PGLEDGER_PGPORT:-5432}
MCP_PORT=${PGLEDGER_MCP_PORT:-8765}
ADMIN_PORT=${PGLEDGER_ADMIN_PORT:-}
ALLOWED_HOSTS=${DBHUB_ALLOWED_HOSTS:-}
ADMIN_ALLOWED_HOSTS=${PGLEDGER_ADMIN_ALLOWED_HOSTS:-}
BASE_DOMAIN=${PGLEDGER_DOMAIN:-}
MCP_HOST=${PGLEDGER_MCP_HOST:-}
ADMIN_HOST=${PGLEDGER_ADMIN_HOST:-}
DOMAIN_MODE=${PGLEDGER_DOMAIN_MODE:-auto}
CF_TUNNEL_ID=${CF_TUNNEL_ID:-}
GOOGLE_CLIENT_ID=${PGLEDGER_ADMIN_GOOGLE_CLIENT_ID:-}
GOOGLE_CLIENT_SECRET=${PGLEDGER_ADMIN_GOOGLE_CLIENT_SECRET:-}
ALLOWED_EMAIL=${PGLEDGER_ADMIN_ALLOWED_EMAIL:-}
REPO_DIR=
REPO_URL=${PGLEDGER_REPO_URL:-https://github.com/jaeseongs95/pg-ledger-mcp.git}
REPO_BRANCH=${PGLEDGER_REPO_BRANCH:-main}
SKIP_ADMIN=0
RESET_PASSWORDS=0
INIT_PROJECT_ID=
INIT_PROJECT_CODE=
INIT_PROJECT_TITLE=

die() { echo "one-click-install.sh: $*" >&2; exit 1; }
usage() {
  cat <<'USAGE'
one-click-install.sh : One-click zero-config installer for pg-ledger-mcp (PostgreSQL schema + DBHub MCP + Admin Web Console + optional Custom Domain).
  sudo deploy/one-click-install.sh [--db ledger] [--pg-port 5432] [--mcp-port 8765] [--admin-port 8865]
                                   [--name pg-ledger-mcp] [--allowed-hosts mcp.example.com] [--admin-allowed-hosts admin.example.com]
                                   [--domain jaeseong.dev] [--mcp-host ledger-mcp.jaeseong.dev] [--admin-host ledger-admin.jaeseong.dev]
                                   [--domain-mode auto|origin-tls|cloudflare-api|cloudflared-config|nginx|allowlist-only] [--cf-tunnel-id <uuid>]
                                   [--google-client-id <id>] [--google-client-secret <secret>] [--allowed-email <email>]
                                   [--repo-dir /opt/pg-ledger-mcp/repo] [--repo-url <url>] [--branch main]
                                   [--project <id> <CODE> <title>] [--skip-admin] [--reset-passwords]
USAGE
  exit "${1:-0}"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --name) NAME=${2:?}; shift 2 ;;
    --db) DB=${2:?}; shift 2 ;;
    --pg-port) PG_PORT=${2:?}; shift 2 ;;
    --mcp-port) MCP_PORT=${2:?}; shift 2 ;;
    --admin-port) ADMIN_PORT=${2:?}; shift 2 ;;
    --allowed-hosts) ALLOWED_HOSTS=${2:?}; shift 2 ;;
    --admin-allowed-hosts) ADMIN_ALLOWED_HOSTS=${2:?}; shift 2 ;;
    --domain) BASE_DOMAIN=${2:?}; shift 2 ;;
    --mcp-host) MCP_HOST=${2:?}; shift 2 ;;
    --admin-host) ADMIN_HOST=${2:?}; shift 2 ;;
    --domain-mode) DOMAIN_MODE=${2:?}; shift 2 ;;
    --cf-tunnel-id) CF_TUNNEL_ID=${2:?}; shift 2 ;;
    --google-client-id) GOOGLE_CLIENT_ID=${2:?}; shift 2 ;;
    --google-client-secret) GOOGLE_CLIENT_SECRET=${2:?}; shift 2 ;;
    --allowed-email) ALLOWED_EMAIL=${2:?}; shift 2 ;;
    --repo-dir) REPO_DIR=${2:?}; shift 2 ;;
    --repo-url) REPO_URL=${2:?}; shift 2 ;;
    --branch) REPO_BRANCH=${2:?}; shift 2 ;;
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
[[ "$PG_PORT" =~ ^[0-9]+$ && "$MCP_PORT" =~ ^[0-9]+$ ]] || die "ports must be numbers"
if [ -n "$ADMIN_PORT" ]; then
  [[ "$ADMIN_PORT" =~ ^[0-9]+$ ]] || die "--admin-port must be a number"
else
  ADMIN_PORT=$((MCP_PORT + 100))
fi

# Resolve REPO checkout: support both direct execution from a checkout and piped execution (`curl ... | sudo bash`)
SCRIPT_SRC=${BASH_SOURCE[0]:-}
REPO=
if [ -n "$REPO_DIR" ] && [ -f "$REPO_DIR/deploy/install.sh" ]; then
  REPO=$(cd "$REPO_DIR" && pwd)
elif [ -n "$SCRIPT_SRC" ] && [ -f "$SCRIPT_SRC" ]; then
  CANDIDATE=$(cd "$(dirname "$SCRIPT_SRC")/.." 2>/dev/null && pwd || true)
  if [ -n "$CANDIDATE" ] && [ -f "$CANDIDATE/deploy/install.sh" ]; then
    REPO=$CANDIDATE
  fi
fi

if [ -z "$REPO" ]; then
  [ -n "$REPO_DIR" ] || REPO_DIR="/opt/$NAME/repo"
  echo "== [0/4] Bootstrapping repository into $REPO_DIR ($REPO_URL @ $REPO_BRANCH)"
  command -v git >/dev/null || die "missing required command: git (needed when running via curl | bash)"
  install -d -m 0755 "$(dirname "$REPO_DIR")"
  if [ -d "$REPO_DIR/.git" ]; then
    git -C "$REPO_DIR" fetch --depth 1 origin "$REPO_BRANCH"
    git -C "$REPO_DIR" checkout -q -f FETCH_HEAD
  else
    git clone --depth 1 --branch "$REPO_BRANCH" "$REPO_URL" "$REPO_DIR"
  fi
  chmod -R a+rX "$REPO_DIR"
  REPO=$(cd "$REPO_DIR" && pwd)
fi

echo "== [1/4] Checking host prerequisites (PostgreSQL >= 15, Node.js >= 22.5, systemd)"
for c in psql pg_dump runuser curl systemctl; do
  command -v "$c" >/dev/null || die "missing required command: $c"
done

NEED_NODE=1
if command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1; then
  if node -e 'const [a,b]=process.versions.node.split(".").map(Number); process.exit(a>22||(a===22&&b>=5)?0:1)' 2>/dev/null; then
    NEED_NODE=0
    echo "    Found Node.js $(node -v)"
  fi
fi

if [ "$NEED_NODE" = 1 ]; then
  if command -v apt-get >/dev/null 2>&1; then
    echo "    Installing Node.js 22 LTS from NodeSource..."
    curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
    apt-get install -y nodejs
    echo "    Installed Node.js $(node -v)"
  else
    die "Node.js >= 22.5 is required (found $(command -v node >/dev/null && node -v || echo 'none'))"
  fi
fi

psql_major=$(psql --version | sed -E 's/[^0-9]*([0-9]+).*/\1/')
[ "$psql_major" -ge 15 ] || die "psql 15 or newer is required (found $psql_major)"
node -e 'const [a,b]=process.versions.node.split(".").map(Number); process.exit(a>22||(a===22&&b>=5)?0:1)' \
  || die "Node.js >= 22.5 is required (found $(node -v))"

echo "== [2/4] Ensuring PostgreSQL database '$DB' exists on port $PG_PORT"
runuser -u postgres -- psql -X -q -v ON_ERROR_STOP=1 -p "$PG_PORT" -d postgres -tAc "SELECT 1" >/dev/null \
  || die "cannot connect to PostgreSQL on port $PG_PORT as postgres"

db_exists=$(runuser -u postgres -- psql -X -q -v ON_ERROR_STOP=1 -p "$PG_PORT" -d postgres -v db="$DB" -tA \
  <<<"SELECT 1 FROM pg_database WHERE datname = :'db'")
if [ "$db_exists" != "1" ]; then
  echo "creating database '$DB' (UTF8)"
  runuser -u postgres -- createdb -p "$PG_PORT" -E UTF8 "$DB"
else
  echo "database '$DB' already exists (preserving existing data)"
fi

echo "== [3/4] Running core installer (schema, roles, DBHub MCP, and Admin Web Console)"
install_args=(--db "$DB" --pg-port "$PG_PORT" --mcp-port "$MCP_PORT" --admin-port "$ADMIN_PORT" --name "$NAME")
[ -z "$ALLOWED_HOSTS" ] || install_args+=(--allowed-hosts "$ALLOWED_HOSTS")
[ -z "$ADMIN_ALLOWED_HOSTS" ] || install_args+=(--admin-allowed-hosts "$ADMIN_ALLOWED_HOSTS")
[ "$SKIP_ADMIN" = 0 ] || install_args+=(--skip-admin)
[ "$RESET_PASSWORDS" = 0 ] || install_args+=(--reset-passwords)

PGLEDGER_ADMIN_GOOGLE_CLIENT_ID="$GOOGLE_CLIENT_ID" \
PGLEDGER_ADMIN_GOOGLE_CLIENT_SECRET="$GOOGLE_CLIENT_SECRET" \
PGLEDGER_ADMIN_ALLOWED_EMAIL="$ALLOWED_EMAIL" \
  "$REPO/deploy/install.sh" "${install_args[@]}"

if [ -n "$INIT_PROJECT_ID" ]; then
  echo "== [3b/4] Ensuring initial project '$INIT_PROJECT_ID' ($INIT_PROJECT_CODE)"
  exists_proj=$(runuser -u postgres -- psql -X -q -v ON_ERROR_STOP=1 -p "$PG_PORT" -d "$DB" -v id="$INIT_PROJECT_ID" -tA \
    <<<"SELECT 1 FROM ledger.project WHERE id = :'id'")
  if [ "$exists_proj" != "1" ]; then
    "$REPO/bin/ledger-admin" --name "$NAME" project-add "$INIT_PROJECT_ID" "$INIT_PROJECT_CODE" "$INIT_PROJECT_TITLE" >/dev/null
    echo "registered project: $INIT_PROJECT_ID ($INIT_PROJECT_CODE)"
  else
    echo "project '$INIT_PROJECT_ID' already exists"
  fi
fi

if [ -n "$BASE_DOMAIN" ] || [ -n "$MCP_HOST" ] || [ -n "$ADMIN_HOST" ]; then
  echo "== [3c/4] Configuring custom domain & Cloudflare/Nginx routing"
  domain_args=(--name "$NAME" --mode "$DOMAIN_MODE")
  [ -z "$BASE_DOMAIN" ] || domain_args+=(--domain "$BASE_DOMAIN")
  [ -z "$MCP_HOST" ] || domain_args+=(--mcp-host "$MCP_HOST")
  [ -z "$ADMIN_HOST" ] || domain_args+=(--admin-host "$ADMIN_HOST")
  [ -z "$CF_TUNNEL_ID" ] || domain_args+=(--cf-tunnel-id "$CF_TUNNEL_ID")
  "$REPO/deploy/setup-domain.sh" "${domain_args[@]}"
fi

ENV_FILE=/etc/$NAME/env
envget() { [ -f "$ENV_FILE" ] && sed -n "s/^$1=//p" "$ENV_FILE" | tail -n 1 || true; }
ADMIN_TLS_CERT=$(envget PGLEDGER_ADMIN_TLS_CERT)
ADMIN_TLS_KEY=$(envget PGLEDGER_ADMIN_TLS_KEY)
ADMIN_SCHEME=http
ADMIN_CURL_FLAGS=(-fsS)
if [ -n "$ADMIN_TLS_CERT" ] && [ -n "$ADMIN_TLS_KEY" ] && [ -s "$ADMIN_TLS_CERT" ] && [ -s "$ADMIN_TLS_KEY" ]; then
  ADMIN_SCHEME=https
  ADMIN_CURL_FLAGS=(-fsSk)
fi

echo "== [4/4] Verifying service readiness"
curl -fsS "http://127.0.0.1:$MCP_PORT/healthz" >/dev/null \
  || die "MCP endpoint http://127.0.0.1:$MCP_PORT/healthz failed health check"
if [ "$SKIP_ADMIN" = 0 ]; then
  curl "${ADMIN_CURL_FLAGS[@]}" "${ADMIN_SCHEME}://127.0.0.1:$ADMIN_PORT/healthz" | grep -q '"service"' \
    || die "Admin web console ${ADMIN_SCHEME}://127.0.0.1:$ADMIN_PORT/healthz failed health check"
fi

cat <<EOF
======================================================================
pg-ledger-mcp one-click installation completed!
======================================================================
  Instance Name : $NAME
  Repository    : $REPO
  Database      : $DB (127.0.0.1:$PG_PORT)
  Credentials   : $ENV_FILE (mode 0600, root only)

  [1] MCP Server (DBHub for AI Agents)
      Service   : $NAME.service
      Local URL : http://127.0.0.1:$MCP_PORT/mcp
      Auth Key  : DBHUB_AUTH_TOKEN (in $ENV_FILE)

  [2] Admin Web Console (Owner Control + Read-Only Explorer)
      Service   : $( [ "$SKIP_ADMIN" = 0 ] && echo "$NAME-admin.service" || echo "skipped (--skip-admin)" )
      Local URL : ${ADMIN_SCHEME}://127.0.0.1:$ADMIN_PORT
      Auth Key  : PGLEDGER_ADMIN_TOKEN (in $ENV_FILE; separated from agent token)
      OAuth     : $( [ -n "$(envget PGLEDGER_ADMIN_ALLOWED_EMAIL)" ] && echo "Google OAuth enabled ($(envget PGLEDGER_ADMIN_ALLOWED_EMAIL))" || echo "Token login (or set PGLEDGER_ADMIN_GOOGLE_CLIENT_ID/SECRET/ALLOWED_EMAIL)" )
      SSH Tunnel: ssh -L $ADMIN_PORT:127.0.0.1:$ADMIN_PORT <user>@<host>
======================================================================
EOF
