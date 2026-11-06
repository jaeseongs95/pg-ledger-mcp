# Sourced only by the disposable e2e upgrade scenario. No fault/gate switches enter the production installer.
schema_snapshot() {
  runuser -u postgres -- pg_dump -p "$PG_PORT" -d "$DB" --schema-only -n ledger \
    | sed '/^\\restrict /d; /^\\unrestrict /d' | sha256sum | cut -d ' ' -f 1
}
copy_installer() {
  local dest=$1
  mkdir -p "$dest"
  cp -a "$REPO/sql" "$REPO/deploy" "$dest/"
}
install_copy() {
  "$1/deploy/install.sh" --db "$DB" --pg-port "$PG_PORT" --mcp-port "$MCP_PORT" --name "$NAME" --schema-only
}
install_rollback_cases() {
  local original_schema dir point out
  original_schema=$(schema_snapshot)
  for point in 16_common_code 17_created_by 20_api; do
    dir=$(mktemp -d)
    copy_installer "$dir"
    printf '\nSELECT 1/0; -- intentional complete-installer failure\n' >> "$dir/sql/$point.sql"
    if out=$(install_copy "$dir" 2>&1); then echo "FAIL  installer accepted failure after $point"; fail=1
    elif grep -q 'division by zero' <<<"$out"; then echo "PASS  installer fails at injected $point boundary"
    else echo "FAIL  unexpected installer error after $point: $out"; fail=1; fi
    printf '%s\n' "$out" > "$EVIDENCE_DIR/rollback-$point.log"
    pass_if "whole schema rolls back after $point" "$(schema_snapshot)" "$original_schema"
    pass_if "all rows roll back after $point" "$(snapshot)" "$before"
    pass_if "no new migration markers after $point" "$(pgsu -d "$DB" -tAc "select count(*) from ledger._migration where step in ('common_code','created_by')")" 0
    rm -rf "$dir"
  done
}
await_database_state() {
  local i
  for i in $(seq 300); do [ "$(pgsu -d "$DB" -tAc "$1")" = t ] && return 0; sleep 0.1; done
  echo "FAIL  timed out waiting for database state: $1" >&2
  return 1
}
concurrent_upgrade() {
  local mode=$1 dir gate migration category_writer severity_writer rc original_schema
  original_schema=$(schema_snapshot)
  dir=$(mktemp -d)
  copy_installer "$dir"
  ATOMIC_DIR=$dir
  printf '\nSELECT pg_advisory_xact_lock(4243); -- test gate after constraint replacement\n' >> "$dir/sql/16_common_code.sql"
  if [ "$mode" = commit ]; then
    sed -i '/^echo "functions, views and grants applied"$/a exit 79 # intentional post-schema install failure' "$dir/deploy/install.sh"
  fi
  if [ "$mode" = rollback ]; then printf '\nSELECT 1/0; -- intentional late failure\n' >> "$dir/sql/20_api.sql"; fi
  PGAPPNAME=e2e-migration-gate pgsu -d "$DB" -c 'SELECT pg_advisory_lock(4243),pg_sleep(300)' > "$dir/gate.log" 2>&1 & gate=$!
  await_database_state "select exists(select 1 from pg_stat_activity where application_name='e2e-migration-gate' and wait_event='PgSleep')"
  PGAPPNAME=e2e-atomic-upgrade install_copy "$dir" > "$dir/install.log" 2>&1 & migration=$!
  await_database_state "select exists(select 1 from pg_stat_activity where application_name='e2e-atomic-upgrade' and wait_event_type='Lock' and query like '%pg_advisory_xact_lock(4243)%')"
  PGAPPNAME=e2e-old-category login_sql ledger_author "select ledger.save(gen_random_uuid(),'claude:e2e-seed','defect','OLD-D-0001',1,jsonb_build_object('category','MISSING'),'e2e: invalid concurrent category')" > "$dir/category.log" 2>&1 & category_writer=$!
  PGAPPNAME=e2e-old-severity login_sql ledger_author "select ledger.save(gen_random_uuid(),'claude:e2e-seed','defect','OLD-D-0001',1,jsonb_build_object('severity','INVALID'),'e2e: invalid concurrent severity')" > "$dir/severity.log" 2>&1 & severity_writer=$!
  await_database_state "select count(*)=2 from pg_stat_activity w where w.application_name in ('e2e-old-category','e2e-old-severity') and exists(select 1 from pg_stat_activity m where m.application_name='e2e-atomic-upgrade' and m.pid=any(pg_blocking_pids(w.pid)))"
  echo "PASS  old category and severity writers wait for the complete $mode upgrade"
  pgsu -d "$DB" -tAc "select pg_terminate_backend(pid) from pg_stat_activity where application_name='e2e-migration-gate'" >/dev/null
  wait "$gate" || true
  if wait "$migration"; then rc=0; else rc=$?; fi
  if [ "$mode" = commit ]; then
    pass_if 'installer fault after complete schema commit is observed' "$rc" 79
    pass_if 'replacement code and creator triggers are installed together' "$(pgsu -d "$DB" -tAc "select count(*) from pg_trigger where tgrelid='ledger.defect'::regclass and tgname in ('defect_codes','defect_created_by')")" 2
  elif [ "$rc" != 0 ] && grep -q 'division by zero' "$dir/install.log"; then echo 'PASS  gated full installer fails at late boundary'
  else echo "FAIL  unexpected gated installer result: $rc $(cat "$dir/install.log")"; fail=1; fi
  if wait "$category_writer"; then echo 'FAIL  concurrent invalid category committed'; fail=1
  elif grep -Eq "$([ "$mode" = commit ] && echo 'CODE_INVALID: defect.category' || echo 'defect_category_fkey')" "$dir/category.log"; then echo "PASS  concurrent invalid category refused after $mode"
  else echo "FAIL  unexpected category result: $(cat "$dir/category.log")"; fail=1; fi
  if wait "$severity_writer"; then echo 'FAIL  concurrent invalid severity committed'; fail=1
  elif grep -Eq "$([ "$mode" = commit ] && echo 'CODE_INVALID: defect.severity' || echo 'defect_severity_check')" "$dir/severity.log"; then echo "PASS  concurrent invalid severity refused after $mode"
  else echo "FAIL  unexpected severity result: $(cat "$dir/severity.log")"; fail=1; fi
  pass_if "concurrent writers leave semantic rows unchanged after $mode" "$(snapshot)" "$before"
  if [ "$mode" = rollback ]; then pass_if 'late failed installer restores whole schema' "$(schema_snapshot)" "$original_schema"; fi
  for log in "$dir"/*.log; do cp "$log" "$EVIDENCE_DIR/$mode-$(basename "$log")"; done
  ATOMIC_DIR=
  rm -rf "$dir"
}
