#!/usr/bin/env bash
# Render the DBHub template from active common codes, without reading password/token values.
set -euo pipefail
REPO=$(cd "$(dirname "$0")/.." && pwd)
NAME=pg-ledger-mcp
if [ "${1:-}" = --name ]; then NAME=${2:?}; shift 2; fi
[ $# -eq 0 ] && [[ "$NAME" =~ ^[a-zA-Z0-9_-]+$ ]] || { echo "usage: render-tools.sh [--name <instance>]" >&2; exit 2; }
ETC=/etc/$NAME
DB=$(sed -n 's/^PGLEDGER_DB=//p' "$ETC/env" | tail -n 1)
PG_PORT=$(sed -n 's/^PGLEDGER_PGPORT=//p' "$ETC/env" | tail -n 1)
tmp=$(mktemp "$ETC/.dbhub.toml.XXXXXX")
trap 'rm -f "$tmp"' EXIT
runuser -u postgres -- psql -X -q -v ON_ERROR_STOP=1 -p "$PG_PORT" -d "$DB" -tAc \
  "SELECT coalesce(jsonb_agg(to_jsonb(c) ORDER BY code_group,sort_order,code),'[]') FROM ledger.common_code c WHERE active" \
  | node -e '
const fs = require("node:fs");
const rows = JSON.parse(fs.readFileSync(0,"utf8"));
const template = fs.readFileSync(process.argv[1],"utf8");
const unresolved = template.replace(/@(CODES|CODELIST):(DEFECT_CATEGORY|DEFECT_SEVERITY|RELATION_KIND)@/g, "");
if (/@(?:CODES|CODELIST):/.test(unresolved)) throw new Error("invalid common-code template placeholder");
const rendered = template.replace(/@(CODES|CODELIST):([A-Z_]+)@/g, (_,kind,group) => {
  const codes = rows.filter(c => c.code_group === group);
  if (kind === "CODELIST") return codes.map(c => JSON.stringify(c.code)).join(", ");
  const description = codes.map(c => `${c.code} ${c.name_ko} (${c.name_en}): ${c.description_ko} / ${c.description_en}`).join("\n")
    || "(없음: ledger-admin category-add로 추가)";
  return JSON.stringify(description).slice(1,-1);
});
fs.writeFileSync(process.argv[2],rendered);
' "$REPO/mcp/dbhub.toml" "$tmp"
chmod 0644 "$tmp"
mv -f "$tmp" "$ETC/dbhub.toml"
