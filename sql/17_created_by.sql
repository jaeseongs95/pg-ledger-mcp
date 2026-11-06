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
    ELSIF t='condition_link' THEN
      -- Both physical link kinds can have the same history entity_id. Untyped lifecycle evidence is ambiguous.
      UPDATE ledger.condition_link x SET created_by=coalesce((
        WITH expected AS (SELECT CASE WHEN x.task_id IS NOT NULL THEN 'condition_task' ELSE 'condition_defect' END AS kind),
        events AS (SELECT h.id,h.author_id,h.op,h.changes->>'kind' AS kind FROM ledger.history h
          WHERE h.entity='condition_link' AND h.entity_id=x.condition_id || '|' || coalesce(x.task_id,x.defect_id)),
        boundary AS (SELECT coalesce(max(e.id),0) AS id FROM events e WHERE e.op='UNLINK' AND e.kind=(SELECT kind FROM expected))
        SELECT e.author_id FROM events e,boundary b WHERE e.op='LINK' AND e.kind=(SELECT kind FROM expected) AND e.id>b.id
          AND NOT EXISTS(SELECT 1 FROM events a WHERE a.id>b.id AND a.op IN ('LINK','UNLINK')
            AND (a.kind IS NULL OR a.kind NOT IN ('condition_task','condition_defect')))
        ORDER BY e.id LIMIT 1),'unknown:legacy');
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
