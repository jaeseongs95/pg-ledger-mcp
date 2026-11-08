#!/usr/bin/env bash
# setup-domain.sh : Automated custom domain & Cloudflare/Nginx configuration for pg-ledger-mcp (MCP + Admin Web Console).
# Run as root on the database/service host. Safe to re-run (idempotent).
#
#   sudo deploy/setup-domain.sh [--name pg-ledger-mcp] [--domain jaeseong.dev]
#                               [--mcp-host ledger-mcp.jaeseong.dev] [--admin-host ledger-admin.jaeseong.dev]
#                               [--mode auto|cloudflare-api|cloudflared-config|nginx|allowlist-only]
#                               [--cf-tunnel-id <uuid>] [--cf-zone jaeseong.dev]
#                               [--cloudflared-config /etc/cloudflared/config.yml]
#                               [--nginx-conf /etc/nginx/sites-available/<name>.conf] [--nginx-dry-run] [--certbot-email <email>]
set -euo pipefail
umask 077

NAME=pg-ledger-mcp
BASE_DOMAIN=
MCP_HOST=
ADMIN_HOST=
MODE=auto
CF_TUNNEL_ID=${CF_TUNNEL_ID:-}
CF_ZONE=${CF_ZONE:-}
CLOUDFLARED_CONFIG=/etc/cloudflared/config.yml
NGINX_CONF=
NGINX_DRY_RUN=0
CERTBOT_EMAIL=${CERTBOT_EMAIL:-}

die() { echo "setup-domain.sh: $*" >&2; exit 1; }
usage() { sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --name) NAME=${2:?}; shift 2 ;;
    --domain) BASE_DOMAIN=${2:?}; shift 2 ;;
    --mcp-host) MCP_HOST=${2:?}; shift 2 ;;
    --admin-host) ADMIN_HOST=${2:?}; shift 2 ;;
    --mode) MODE=${2:?}; shift 2 ;;
    --cf-tunnel-id) CF_TUNNEL_ID=${2:?}; shift 2 ;;
    --cf-zone) CF_ZONE=${2:?}; shift 2 ;;
    --cloudflared-config) CLOUDFLARED_CONFIG=${2:?}; shift 2 ;;
    --nginx-conf) NGINX_CONF=${2:?}; shift 2 ;;
    --nginx-dry-run) NGINX_DRY_RUN=1; shift ;;
    --certbot-email) CERTBOT_EMAIL=${2:?}; shift 2 ;;
    -h|--help) usage ;;
    *) echo "unknown argument: $1" >&2; usage 2 ;;
  esac
done

[ "$(id -u)" = 0 ] || die "run as root"
[[ "$NAME" =~ ^[a-z][a-z0-9-]{0,40}$ ]] || die "--name must match ^[a-z][a-z0-9-]{0,40}$"

if [ -n "$BASE_DOMAIN" ]; then
  BASE_DOMAIN=$(printf '%s' "$BASE_DOMAIN" | tr '[:upper:]' '[:lower:]')
  [ -n "$MCP_HOST" ] || MCP_HOST="ledger-mcp.$BASE_DOMAIN"
  [ -n "$ADMIN_HOST" ] || ADMIN_HOST="ledger-admin.$BASE_DOMAIN"
  [ -n "$CF_ZONE" ] || CF_ZONE="$BASE_DOMAIN"
fi

MCP_HOST=$(printf '%s' "$MCP_HOST" | tr '[:upper:]' '[:lower:]')
ADMIN_HOST=$(printf '%s' "$ADMIN_HOST" | tr '[:upper:]' '[:lower:]')

[ -n "$MCP_HOST" ] || [ -n "$ADMIN_HOST" ] || die "pass --domain <domain> (e.g. jaeseong.dev) or --mcp-host / --admin-host"

validate_fqdn() {
  local h=$1
  [[ "$h" =~ ^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$ ]] \
    || die "invalid domain hostname: $h"
}
[ -z "$MCP_HOST" ] || validate_fqdn "$MCP_HOST"
[ -z "$ADMIN_HOST" ] || validate_fqdn "$ADMIN_HOST"

ENV_FILE="/etc/$NAME/env"
[ -f "$ENV_FILE" ] || die "$ENV_FILE not found (install $NAME first)"

envget() { sed -n "s/^$1=//p" "$ENV_FILE" | tail -n 1 || true; }
MCP_PORT=$(envget PGLEDGER_MCP_PORT); [ -n "$MCP_PORT" ] || MCP_PORT=8765
ADMIN_PORT=$(envget PGLEDGER_ADMIN_PORT); [ -n "$ADMIN_PORT" ] || ADMIN_PORT=$((MCP_PORT + 100))

# ---------- 1. Update DBHUB_ALLOWED_HOSTS & PGLEDGER_ADMIN_ALLOWED_HOSTS in /etc/$NAME/env ----------
merge_csv_host() {
  local csv=$1 host=$2
  [ -n "$host" ] || { printf '%s' "$csv"; return; }
  case ",$csv," in
    *,"$host",*) printf '%s' "$csv" ;;
    *) printf '%s' "${csv:+$csv,}$host" ;;
  esac
}

CUR_MCP_HOSTS=$(envget DBHUB_ALLOWED_HOSTS)
CUR_ADMIN_HOSTS=$(envget PGLEDGER_ADMIN_ALLOWED_HOSTS)
[ -n "$CUR_MCP_HOSTS" ] || CUR_MCP_HOSTS="localhost,127.0.0.1"
[ -n "$CUR_ADMIN_HOSTS" ] || CUR_ADMIN_HOSTS="localhost,127.0.0.1"

NEW_MCP_HOSTS=$(merge_csv_host "$CUR_MCP_HOSTS" "$MCP_HOST")
NEW_ADMIN_HOSTS=$(merge_csv_host "$CUR_ADMIN_HOSTS" "$ADMIN_HOST")
# Also allow MCP_HOST on Admin if user shares or inspects via same domain
if [ -n "$MCP_HOST" ]; then
  NEW_ADMIN_HOSTS=$(merge_csv_host "$NEW_ADMIN_HOSTS" "$MCP_HOST")
fi

node - "$ENV_FILE" "$NEW_MCP_HOSTS" "$NEW_ADMIN_HOSTS" <<'NODE_EOF'
const fs = require('node:fs');
const [envPath, mcpHosts, adminHosts] = process.argv.slice(2);
const lines = fs.readFileSync(envPath, 'utf8').split(/\r?\n/);
let hasMcp = false;
let hasAdmin = false;
const out = lines
  .filter((l, idx, arr) => !(idx === arr.length - 1 && l === ''))
  .map((line) => {
    if (line.startsWith('DBHUB_ALLOWED_HOSTS=')) {
      hasMcp = true;
      return `DBHUB_ALLOWED_HOSTS=${mcpHosts}`;
    }
    if (line.startsWith('PGLEDGER_ADMIN_ALLOWED_HOSTS=')) {
      hasAdmin = true;
      return `PGLEDGER_ADMIN_ALLOWED_HOSTS=${adminHosts}`;
    }
    return line;
  });
if (!hasMcp) out.push(`DBHUB_ALLOWED_HOSTS=${mcpHosts}`);
if (!hasAdmin) out.push(`PGLEDGER_ADMIN_ALLOWED_HOSTS=${adminHosts}`);
const tmp = `${envPath}.tmp.${process.pid}`;
fs.writeFileSync(tmp, out.join('\n') + '\n', { mode: 0o600 });
fs.renameSync(tmp, envPath);
NODE_EOF

echo "updated $ENV_FILE:"
echo "  DBHUB_ALLOWED_HOSTS=$NEW_MCP_HOSTS"
echo "  PGLEDGER_ADMIN_ALLOWED_HOSTS=$NEW_ADMIN_HOSTS"

# Restart active services so new Host/Origin allowlists take effect immediately, and wait for /healthz readiness
if systemctl is-active --quiet "$NAME.service" 2>/dev/null; then
  systemctl restart "$NAME.service"
  deadline=$((SECONDS + 30))
  until curl -fsS "http://127.0.0.1:$MCP_PORT/healthz" >/dev/null 2>&1; do
    [ $SECONDS -lt $deadline ] || die "$NAME.service did not answer http://127.0.0.1:$MCP_PORT/healthz after restart"
    sleep 1
  done
fi
if systemctl is-active --quiet "$NAME-admin.service" 2>/dev/null; then
  systemctl restart "$NAME-admin.service"
  deadline=$((SECONDS + 30))
  until systemctl is-active --quiet "$NAME-admin.service" && curl -fsS "http://127.0.0.1:$ADMIN_PORT/healthz" 2>/dev/null | grep -q '"service"'; do
    [ $SECONDS -lt $deadline ] || die "$NAME-admin.service did not answer http://127.0.0.1:$ADMIN_PORT/healthz after restart"
    sleep 1
  done
fi

# ---------- 2. Auto-detect mode if 'auto' ----------
if [ "$MODE" = "auto" ]; then
  if [ -n "${CF_API_TOKEN:-}" ] && [ -n "$CF_TUNNEL_ID" ]; then
    MODE="cloudflare-api"
  elif [ -f "$CLOUDFLARED_CONFIG" ]; then
    MODE="cloudflared-config"
  elif command -v nginx >/dev/null 2>&1; then
    MODE="nginx"
  else
    MODE="allowlist-only"
  fi
fi

# Extract tunnel UUID from /etc/cloudflared/token if CF_TUNNEL_ID is not set and token file exists
if [ -z "$CF_TUNNEL_ID" ] && [ -f /etc/cloudflared/token ]; then
  CF_TUNNEL_ID=$(node -e '
    const fs = require("node:fs");
    try {
      const raw = fs.readFileSync("/etc/cloudflared/token", "utf8").trim();
      const obj = JSON.parse(Buffer.from(raw, "base64").toString("utf8"));
      if (obj && obj.t) process.stdout.write(String(obj.t));
    } catch {}
  ' || true)
fi

case "$MODE" in
  allowlist-only)
    echo "mode=allowlist-only: Host/Origin allowlists updated and services restarted."
    ;;

  cloudflare-api)
    [ -n "${CF_API_TOKEN:-}" ] || die "CF_API_TOKEN environment variable is required for --mode cloudflare-api"
    [ -n "$CF_TUNNEL_ID" ] || die "--cf-tunnel-id <uuid> (or /etc/cloudflared/token) is required for --mode cloudflare-api"
    if [ -z "$CF_ZONE" ]; then
      target_host=${MCP_HOST:-$ADMIN_HOST}
      CF_ZONE=$(printf '%s' "$target_host" | awk -F. '{print $(NF-1)"."$NF}')
    fi
    echo "mode=cloudflare-api: Configuring Cloudflare Tunnel ($CF_TUNNEL_ID) and DNS records in zone '$CF_ZONE'..."
    CF_API_TOKEN="$CF_API_TOKEN" node - "$CF_ZONE" "$CF_TUNNEL_ID" "$MCP_HOST" "$MCP_PORT" "$ADMIN_HOST" "$ADMIN_PORT" <<'CF_EOF'
const [zoneName, tunnelId, mcpHost, mcpPort, adminHost, adminPort] = process.argv.slice(2);
const token = process.env.CF_API_TOKEN;
const headers = {
  Authorization: `Bearer ${token}`,
  'Content-Type': 'application/json'
};

async function cfFetch(path, options = {}) {
  const resp = await fetch(`https://api.cloudflare.com/client/v4${path}`, {
    ...options,
    headers: { ...headers, ...(options.headers || {}) }
  });
  const data = await resp.json();
  if (!resp.ok || !data.success) {
    const msg = (data.errors || []).map((e) => `${e.code}: ${e.message}`).join('; ') || `HTTP ${resp.status}`;
    throw new Error(`Cloudflare API error on ${path}: ${msg}`);
  }
  return data.result;
}

(async () => {
  // 1. Lookup Zone ID and Account ID
  const zones = await cfFetch(`/zones?name=${encodeURIComponent(zoneName)}`);
  if (!Array.isArray(zones) || zones.length === 0) {
    throw new Error(`Cloudflare zone '${zoneName}' not found or token lacks Zone:Read permission.`);
  }
  const zoneId = zones[0].id;
  const accountId = process.env.CF_ACCOUNT_ID || zones[0].account?.id;
  if (!accountId) {
    throw new Error('Could not determine Cloudflare Account ID; set CF_ACCOUNT_ID.');
  }

  // 2. Fetch existing Tunnel configuration & merge ingress rules
  const curCfg = await cfFetch(`/accounts/${accountId}/cfd_tunnel/${tunnelId}/configurations`).catch(() => ({ config: { ingress: [] } }));
  const configObj = (curCfg && curCfg.config && typeof curCfg.config === 'object') ? curCfg.config : {};
  const existingIngress = Array.isArray(configObj.ingress) ? configObj.ingress : [];

  const desired = [];
  if (mcpHost) desired.push({ hostname: mcpHost, service: `http://127.0.0.1:${mcpPort}`, originRequest: {} });
  if (adminHost) desired.push({ hostname: adminHost, service: `http://127.0.0.1:${adminPort}`, originRequest: {} });
  const desiredHosts = new Set(desired.map((d) => d.hostname));

  const preserved = existingIngress.filter((r) => r && r.hostname && !desiredHosts.has(r.hostname));
  const catchAll = existingIngress.find((r) => r && !r.hostname) || { service: 'http_status:404' };
  const nextIngress = [...desired, ...preserved, catchAll];

  await cfFetch(`/accounts/${accountId}/cfd_tunnel/${tunnelId}/configurations`, {
    method: 'PUT',
    body: JSON.stringify({
      config: {
        ...configObj,
        ingress: nextIngress
      }
    })
  });
  console.log(`  [Cloudflare Tunnel] Updated remote ingress rules for tunnel ${tunnelId}`);

  // 3. Upsert proxied CNAME DNS records pointing to <tunnelId>.cfargotunnel.com
  const targetCname = `${tunnelId}.cfargotunnel.com`;
  for (const host of [mcpHost, adminHost].filter(Boolean)) {
    const existingRecords = await cfFetch(`/zones/${zoneId}/dns_records?name=${encodeURIComponent(host)}`);
    const payload = {
      type: 'CNAME',
      name: host,
      content: targetCname,
      proxied: true,
      ttl: 1,
      comment: 'Managed by pg-ledger-mcp deploy/setup-domain.sh'
    };
    const match = Array.isArray(existingRecords) ? existingRecords.find((r) => r.type === 'CNAME' || r.name === host) : null;
    if (match) {
      await cfFetch(`/zones/${zoneId}/dns_records/${match.id}`, {
        method: 'PUT',
        body: JSON.stringify(payload)
      });
      console.log(`  [Cloudflare DNS] Updated proxied CNAME ${host} -> ${targetCname}`);
    } else {
      await cfFetch(`/zones/${zoneId}/dns_records`, {
        method: 'POST',
        body: JSON.stringify(payload)
      });
      console.log(`  [Cloudflare DNS] Created proxied CNAME ${host} -> ${targetCname}`);
    }
  }
})().catch((err) => {
  console.error(`setup-domain.sh: ${err.message}`);
  process.exit(1);
});
CF_EOF
    ;;

  cloudflared-config)
    install -d -m 0755 "$(dirname "$CLOUDFLARED_CONFIG")"
    node - "$CLOUDFLARED_CONFIG" "$CF_TUNNEL_ID" "$MCP_HOST" "$MCP_PORT" "$ADMIN_HOST" "$ADMIN_PORT" <<'YAML_EOF'
const fs = require('node:fs');
const [cfgPath, tunnelId, mcpHost, mcpPort, adminHost, adminPort] = process.argv.slice(2);

let content = fs.existsSync(cfgPath) ? fs.readFileSync(cfgPath, 'utf8') : '';
const beginMarker = '# --- BEGIN pg-ledger-mcp ingress ---';
const endMarker = '# --- END pg-ledger-mcp ingress ---';

const rules = [beginMarker];
if (mcpHost) {
  rules.push(`  - hostname: ${mcpHost}`);
  rules.push(`    service: http://127.0.0.1:${mcpPort}`);
}
if (adminHost) {
  rules.push(`  - hostname: ${adminHost}`);
  rules.push(`    service: http://127.0.0.1:${adminPort}`);
}
rules.push(endMarker);
const block = rules.join('\n');

const esc = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const blockRegex = new RegExp(`${esc(beginMarker)}[\\s\\S]*?${esc(endMarker)}`, 'm');

if (blockRegex.test(content)) {
  content = content.replace(blockRegex, block);
} else if (/^ingress:\s*$/m.test(content)) {
  content = content.replace(/^ingress:\s*$/m, `ingress:\n${block}`);
} else {
  const header = tunnelId ? `tunnel: ${tunnelId}\n` : '';
  content = `${header}ingress:\n${block}\n  - service: http_status:404\n`;
}

fs.writeFileSync(cfgPath, content, { mode: 0o600 });
YAML_EOF
    echo "mode=cloudflared-config: Updated ingress rules in $CLOUDFLARED_CONFIG"
    if command -v cloudflared >/dev/null 2>&1 && [ -n "$CF_TUNNEL_ID" ] && [ -f /root/.cloudflared/cert.pem ]; then
      [ -z "$MCP_HOST" ] || cloudflared tunnel route dns --overwrite-dns "$CF_TUNNEL_ID" "$MCP_HOST" || true
      [ -z "$ADMIN_HOST" ] || cloudflared tunnel route dns --overwrite-dns "$CF_TUNNEL_ID" "$ADMIN_HOST" || true
    fi
    if systemctl is-active --quiet cloudflared.service 2>/dev/null; then
      systemctl restart cloudflared.service
      echo "restarted cloudflared.service"
    fi
    ;;

  nginx)
    [ -n "$NGINX_CONF" ] || NGINX_CONF="/etc/nginx/sites-available/$NAME.conf"
    install -d -m 0755 "$(dirname "$NGINX_CONF")"
    {
      echo "# Generated by pg-ledger-mcp deploy/setup-domain.sh for instance $NAME"
      if [ -n "$MCP_HOST" ]; then
        cat <<EOF
server {
    listen 80;
    listen [::]:80;
    server_name $MCP_HOST;

    location / {
        proxy_pass http://127.0.0.1:$MCP_PORT;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_set_header Connection "";
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 3600s;
    }
}
EOF
      fi
      if [ -n "$ADMIN_HOST" ]; then
        cat <<EOF
server {
    listen 80;
    listen [::]:80;
    server_name $ADMIN_HOST;

    location / {
        proxy_pass http://127.0.0.1:$ADMIN_PORT;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }
}
EOF
      fi
    } > "$NGINX_CONF"
    chmod 0644 "$NGINX_CONF"
    echo "mode=nginx: Rendered Nginx config to $NGINX_CONF"

    if [ "$NGINX_DRY_RUN" = 0 ]; then
      if [ -d /etc/nginx/sites-enabled ]; then
        ln -sfn "$NGINX_CONF" "/etc/nginx/sites-enabled/$NAME.conf"
      fi
      nginx -t
      systemctl reload nginx || systemctl restart nginx
      if [ -n "$CERTBOT_EMAIL" ] && command -v certbot >/dev/null 2>&1; then
        cert_domains=()
        [ -z "$MCP_HOST" ] || cert_domains+=(-d "$MCP_HOST")
        [ -z "$ADMIN_HOST" ] || cert_domains+=(-d "$ADMIN_HOST")
        certbot --nginx "${cert_domains[@]}" --non-interactive --agree-tos -m "$CERTBOT_EMAIL" --redirect
      fi
    fi
    ;;

  *)
    die "unknown --mode: $MODE (use auto, cloudflare-api, cloudflared-config, nginx, or allowlist-only)"
    ;;
esac

cat <<EOF
======================================================================
pg-ledger-mcp domain configuration completed ($NAME)
======================================================================
  Mode          : $MODE
  MCP Domain    : ${MCP_HOST:-(not set)} -> http://127.0.0.1:$MCP_PORT/mcp
  Admin Domain  : ${ADMIN_HOST:-(not set)} -> http://127.0.0.1:$ADMIN_PORT
  Env File      : $ENV_FILE
======================================================================
EOF
