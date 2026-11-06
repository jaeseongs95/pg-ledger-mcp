-- 15_defect_case.sql : observed cases of a defect. One defect = one root cause; each observation of it (symptom, log,
-- user report) is a row of ledger.defect_case under it, written by ledger.record_case (sql/20_api.sql).
-- Applied after 10_tables.sql and before 20_api.sql on every install (deploy/install.sh). It runs once per database:
-- when ledger._migration holds step 'defect_case' it does nothing. Existing rows are not changed; history rows are only
-- re-checked against the widened entity CHECK.
\set ON_ERROR_STOP on
\if :{?ledger_install_transaction}
\else
BEGIN;
\endif
SET ROLE ledger_owner;
DO $m$
DECLARE v_check text; n int;
BEGIN
  IF EXISTS (SELECT 1 FROM ledger._migration WHERE step = 'defect_case') THEN RETURN; END IF;

  CREATE TABLE ledger.defect_case (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    defect_id text NOT NULL REFERENCES ledger.defect,
    observation text NOT NULL CHECK (length(btrim(observation)) BETWEEN 1 AND 4000),   -- what was seen
    evidence text CHECK (evidence IS NULL OR length(btrim(evidence)) BETWEEN 1 AND 2000), -- request id, file:line, log location
    user_quote text CHECK (user_quote IS NULL OR length(user_quote) <= 2000),            -- the user's own words, verbatim
    observed_at timestamptz,                                                             -- when it was seen; NULL = not known
    recorded_by text NOT NULL CHECK (ledger._author_ok(recorded_by)),
    recorded_at timestamptz NOT NULL
  );
  CREATE INDEX defect_case_defect_idx ON ledger.defect_case (defect_id, id);
  -- v_defect walks merged_into backwards to collect the cases of merged defects
  CREATE INDEX defect_merged_into_idx ON ledger.defect (merged_into) WHERE merged_into IS NOT NULL;

  -- history.entity gains 'defect_case'. The CHECK is found by its column, not by name.
  SELECT string_agg(c.conname, ','), count(*) INTO v_check, n FROM pg_constraint c
   WHERE c.conrelid = 'ledger.history'::regclass AND c.contype = 'c'
     AND c.conkey = ARRAY[(SELECT attnum FROM pg_attribute WHERE attrelid = 'ledger.history'::regclass AND attname = 'entity')];
  IF n <> 1 THEN RAISE EXCEPTION 'defect_case: expected one CHECK on ledger.history(entity), found % (%)', n, v_check; END IF;
  EXECUTE format('ALTER TABLE ledger.history DROP CONSTRAINT %I', v_check);
  ALTER TABLE ledger.history ADD CONSTRAINT history_entity_check CHECK (entity IN
    ('defect','task','condition','defect_fix','defect_task','defect_relation','condition_link','defect_case'));

  INSERT INTO ledger._migration (step, detail) VALUES ('defect_case', jsonb_build_object('by', session_user));
END $m$;
RESET ROLE;
\if :{?ledger_install_transaction}
\else
COMMIT;
\endif
