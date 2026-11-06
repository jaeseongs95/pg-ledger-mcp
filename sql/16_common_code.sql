-- 16_common_code.sql : one table of common codes (ledger.common_code) for every coded column, and the map of which
-- column uses which code group (ledger.common_code_use). A trigger on each table checks a written code against the table,
-- so a new code is a row, not a schema change. Inactive codes stay valid on rows that already hold them and are refused
-- when written; a column marked always_active (defect.severity) must hold an active code on every write of its row.
-- Applied after 15_defect_case.sql and before 20_api.sql on every install (deploy/install.sh). It runs once per
-- database: when ledger._migration holds step 'common_code' it does nothing. Existing rows are not changed; the value
-- lists that were CHECKs move into the table, and ledger.category becomes a view of group DEFECT_CATEGORY.
\set ON_ERROR_STOP on
BEGIN;
SET ROLE ledger_owner;
DO $m$
DECLARE r record; v_con text; n int;
BEGIN
  IF EXISTS (SELECT 1 FROM ledger._migration WHERE step = 'common_code') THEN RETURN; END IF;

  CREATE TABLE ledger.common_code (
    code_group text NOT NULL CHECK (code_group ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    code text NOT NULL CHECK (length(btrim(code)) BETWEEN 1 AND 64),
    name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 200),
    description text NOT NULL CHECK (length(description) <= 2000),
    sort_order int NOT NULL DEFAULT 0,
    active boolean NOT NULL DEFAULT true,   -- false: kept for rows that hold it, refused on new writes
    PRIMARY KEY (code_group, code)
  );
  CREATE TABLE ledger.common_code_use (
    table_name text NOT NULL,
    column_name text NOT NULL,
    code_group text NOT NULL,
    always_active boolean NOT NULL DEFAULT false,   -- true: every write of the row needs an active code here (required value)
    PRIMARY KEY (table_name, column_name)
  );

  INSERT INTO ledger.common_code (code_group, code, name, description, sort_order, active) VALUES
    ('DEFECT_SEVERITY', 'P1', 'Critical', 'Blocks the user''s work or reports false completion', 1, true),
    ('DEFECT_SEVERITY', 'P2', 'Major', 'Wrong result or lost work with a workaround', 2, true),
    ('DEFECT_SEVERITY', 'P3', 'Minor', 'Inconvenience, cost or wording', 3, true),
    ('DEFECT_SEVERITY', 'UNRATED', 'Unrated', 'Older rows without a severity; rate them (P1, P2 or P3) on the next write', 9, false),
    ('DEFECT_STATUS', 'OPEN', 'Open', 'Not fixed yet', 1, true),
    ('DEFECT_STATUS', 'SOURCE_FIXED', 'Source fixed', 'A fix commit is recorded', 2, true),
    ('DEFECT_STATUS', 'VERIFIED', 'Verified', 'Another session checked the fix (reviewer login)', 3, true),
    ('DEFECT_STATUS', 'NOT_DEFECT', 'Not a defect', 'Judged not a defect (evidence_uri)', 4, true),
    ('DEFECT_STATUS', 'DISPUTED', 'Disputed', 'Judgement contested (evidence_uri)', 5, true),
    ('DEFECT_STATUS', 'MERGED', 'Merged', 'Same root cause as merged_into', 6, true),
    ('TASK_STATE', 'PLANNED', 'Planned', 'Recorded, not started', 1, true),
    ('TASK_STATE', 'ACTIVE', 'Active', 'In progress', 2, true),
    ('TASK_STATE', 'BLOCKED', 'Blocked', 'Waiting on something outside the task', 3, true),
    ('TASK_STATE', 'REPORTED_DONE', 'Reported done', 'Done according to its owner (evidence_uri)', 4, true),
    ('TASK_STATE', 'VERIFIED', 'Verified', 'Another session checked it (reviewer login)', 5, true),
    ('TASK_STATE', 'CANCELLED', 'Cancelled', 'Will not be done', 6, true),
    ('TASK_STATE', 'UNKNOWN', 'Unknown', 'Imported from an older ledger without a state', 9, false),
    ('CONDITION_STATE', 'UNKNOWN', 'Unknown', 'Not judged yet', 1, true),
    ('CONDITION_STATE', 'MET', 'Met', 'Judged met (reviewer login, evidence_uri)', 2, true),
    ('CONDITION_STATE', 'NOT_MET', 'Not met', 'Judged not met (reviewer login, evidence_uri)', 3, true),
    ('RELATION_KIND', 'same_site', 'Same site', 'Same place in the code or product', 1, true),
    ('RELATION_KIND', 'caused_by', 'Caused by', 'a is caused by b', 2, true),
    ('RELATION_KIND', 'blocks', 'Blocks', 'a blocks b', 3, true),
    ('RELATION_KIND', 'same_family', 'Same family', 'Similar defects with different root causes', 4, true),
    ('RELATION_KIND', 'same_fix_unit', 'Same fix unit', 'Fixed together in one change', 5, true),
    ('CI_CONCLUSION', 'success', 'Success', 'CI run passed', 1, true),
    ('CI_CONCLUSION', 'failure', 'Failure', 'CI run failed', 2, true),
    ('CI_CONCLUSION', 'cancelled', 'Cancelled', 'CI run cancelled', 3, true),
    ('GRANT_SCOPE', 'defect', 'Defect', 'Save defects, record cases, defect links', 1, true),
    ('GRANT_SCOPE', 'task', 'Task', 'Save tasks and conditions, condition links', 2, true),
    ('GRANT_SCOPE', 'fix', 'Fix', 'Record fix commits', 3, true),
    ('GRANT_SCOPE', 'verify', 'Verify', 'Verification through the reviewer login', 4, true);
  -- categories were rows of ledger.category (added with ledger-admin category-add); they keep their codes and texts
  INSERT INTO ledger.common_code (code_group, code, name, description, sort_order)
  SELECT 'DEFECT_CATEGORY', code, name, definition, 0 FROM ledger.category;

  INSERT INTO ledger.common_code_use (table_name, column_name, code_group, always_active) VALUES
    ('defect', 'category', 'DEFECT_CATEGORY', false),
    ('defect', 'severity', 'DEFECT_SEVERITY', true),
    ('defect', 'status', 'DEFECT_STATUS', false),
    ('task', 'state', 'TASK_STATE', false),
    ('condition', 'state', 'CONDITION_STATE', false),
    ('defect_relation', 'kind', 'RELATION_KIND', false),
    ('defect_fix', 'ci_conclusion', 'CI_CONCLUSION', false),
    ('session_grant', 'scope', 'GRANT_SCOPE', false);

  -- every code already stored must be in the table (the trigger refuses unknown codes on the next write of the row)
  FOR r IN SELECT * FROM ledger.common_code_use LOOP
    EXECUTE format('SELECT count(*) FROM ledger.%I t WHERE t.%I IS NOT NULL AND NOT EXISTS
                      (SELECT 1 FROM ledger.common_code c WHERE c.code_group = $1 AND c.code = t.%I)', r.table_name, r.column_name, r.column_name)
      INTO n USING r.code_group;
    IF n <> 0 THEN RAISE EXCEPTION 'common_code: % row(s) of ledger.%.% hold a code missing from group %', n, r.table_name, r.column_name, r.code_group; END IF;
  END LOOP;

  -- the value-list CHECKs of the coded columns (one column each) give way to the table; found by column, not by name
  FOR r IN SELECT * FROM ledger.common_code_use WHERE column_name <> 'category' LOOP
    FOR v_con IN SELECT c.conname FROM pg_constraint c
                  WHERE c.conrelid = format('ledger.%I', r.table_name)::regclass AND c.contype = 'c'
                    AND c.conkey = ARRAY[(SELECT attnum FROM pg_attribute WHERE attrelid = format('ledger.%I', r.table_name)::regclass AND attname = r.column_name)] LOOP
      EXECUTE format('ALTER TABLE ledger.%I DROP CONSTRAINT %I', r.table_name, v_con);
    END LOOP;
  END LOOP;
  SELECT string_agg(conname, ',') INTO v_con FROM pg_constraint WHERE conrelid = 'ledger.defect'::regclass AND confrelid = 'ledger.category'::regclass;
  IF v_con IS NULL OR v_con LIKE '%,%' THEN RAISE EXCEPTION 'common_code: expected one foreign key ledger.defect -> ledger.category, found %', v_con; END IF;
  EXECUTE format('ALTER TABLE ledger.defect DROP CONSTRAINT %I', v_con);
  DROP TABLE ledger.category;
  CREATE VIEW ledger.category AS
    SELECT code, name, description AS definition FROM ledger.common_code WHERE code_group = 'DEFECT_CATEGORY';

  INSERT INTO ledger._migration (step, detail) VALUES ('common_code', jsonb_build_object('by', session_user));
END $m$;
RESET ROLE;
COMMIT;
