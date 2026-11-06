-- One-time provenance backfill; 20_api creates the insert/update stamps after this migration.
\set ON_ERROR_STOP on
\if :{?ledger_install_transaction}
\else
BEGIN;
\endif
SET ROLE ledger_owner;
DO $m$
DECLARE t text; key_sql text; preferred text;
BEGIN
  IF EXISTS (SELECT 1 FROM ledger._migration WHERE step='created_by') THEN RETURN; END IF;
  FOREACH t IN ARRAY ARRAY['project','task','defect','condition','defect_task','defect_relation','condition_link','common_code'] LOOP
    EXECUTE format('ALTER TABLE ledger.%I ADD COLUMN created_by text',t);
    IF t IN ('project','common_code') THEN
      EXECUTE format('UPDATE ledger.%I SET created_by=''user:owner''',t);
    ELSE
      key_sql := CASE t WHEN 'defect_task' THEN 'x.defect_id || ''|'' || x.task_id'
        WHEN 'defect_relation' THEN 'x.a || ''|'' || x.b || ''|'' || x.kind'
        WHEN 'condition_link' THEN 'x.condition_id || ''|'' || coalesce(x.task_id,x.defect_id)' ELSE 'x.id' END;
      preferred := CASE WHEN t IN ('task','defect','condition') THEN 'CREATE' ELSE 'LINK' END;
      EXECUTE format('UPDATE ledger.%I x SET created_by=coalesce((SELECT h.author_id FROM ledger.history h
        WHERE h.entity=$1 AND h.entity_id=%s AND ($2=''CREATE'' OR (h.op=''LINK'' AND h.id > coalesce((SELECT max(u.id) FROM ledger.history u WHERE u.entity=h.entity AND u.entity_id=h.entity_id AND u.op=''UNLINK''),0))) ORDER BY CASE WHEN h.op IN ($2,''IMPORT'') THEN 0 ELSE 1 END,CASE WHEN $2=''LINK'' THEN h.id END,h.at,h.id LIMIT 1),''unknown:legacy'')',t,key_sql)
        USING t,preferred;
    END IF;
    EXECUTE format('ALTER TABLE ledger.%I ALTER COLUMN created_by SET NOT NULL',t);
  END LOOP;
  INSERT INTO ledger._migration(step,detail) VALUES ('created_by',jsonb_build_object('by',session_user));
END $m$;
RESET ROLE;
\if :{?ledger_install_transaction}
\else
COMMIT;
\endif
