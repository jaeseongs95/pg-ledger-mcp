-- 20_api.sql : functions, views and grants of schema "ledger". Idempotent (CREATE OR REPLACE / DROP VIEW + CREATE).
-- Run as a superuser after 10_tables.sql; safe to re-run (deploy/install.sh applies it on every install). Objects are owned by ledger_owner.
\set ON_ERROR_STOP on
\if :{?ledger_install_transaction}
\else
BEGIN;
\endif
SET ROLE ledger_owner;

CREATE OR REPLACE FUNCTION ledger._author_ok(p text) RETURNS boolean LANGUAGE sql IMMUTABLE AS
$$ SELECT coalesce(p = 'user:owner' OR (p ~ '^[a-z][a-z0-9-]{0,31}:[A-Za-z0-9._-]{1,128}$' AND p !~ '^user:'), false) $$;
CREATE OR REPLACE FUNCTION ledger._sid(p text) RETURNS text LANGUAGE sql IMMUTABLE AS $$ SELECT regexp_replace(p, '^agent:', '') $$;

-- project of a defect (NULL when the defect does not exist)
CREATE OR REPLACE FUNCTION ledger._defect_project(p_defect text) RETURNS text LANGUAGE sql STABLE SET search_path = ledger, pg_temp AS
$$ SELECT project_id FROM ledger.defect WHERE id = p_defect $$;

-- ---------- internal helpers ----------
-- Who may write: role login, author id format, live session grant. The grant row is locked FOR SHARE, so a concurrent
-- revoke_session waits for this transaction (the write commits before the revocation) and a committed revocation is seen.
CREATE OR REPLACE FUNCTION ledger._begin(p_author text, p_scope text, p_project text) RETURNS void
LANGUAGE plpgsql SET search_path = ledger, pg_temp AS $f$
BEGIN
  IF session_user NOT IN ('ledger_author', 'ledger_reviewer') THEN
    RAISE EXCEPTION 'LOGIN_NOT_ALLOWED: writes come through login ledger_author or ledger_reviewer (session_user=%)', session_user USING ERRCODE = '42501';
  END IF;
  IF p_scope = 'verify' AND session_user <> 'ledger_reviewer' THEN
    RAISE EXCEPTION 'VERIFY_NEEDS_REVIEWER_LOGIN: verification is recorded only through login ledger_reviewer (session_user=%)', session_user USING ERRCODE = '42501';
  END IF;
  IF p_author IS NULL THEN
    RAISE EXCEPTION 'HOST_ATTESTATION_REQUIRED: 호스트 훅이 세션을 넣지 않았다(pg-ledger-mcp 클라이언트 훅 설치 필요)' USING ERRCODE = '22023';
  END IF;
  IF NOT ledger._author_ok(p_author) THEN
    RAISE EXCEPTION 'AUTHOR_ID_FORMAT: author_id must be <vendor>:<session-id> (vendor lowercase, e.g. claude:1234abcd) or user:owner (got %)', coalesce(p_author, 'NULL') USING ERRCODE = '22023';
  END IF;
  PERFORM set_config('ledger.author',p_author,true);
  IF p_project IS NULL THEN RAISE EXCEPTION 'project unknown' USING ERRCODE = '22023'; END IF;
  PERFORM 1 FROM ledger.session_grant g
   WHERE g.author_id = p_author AND g.project = p_project AND g.scope = p_scope
     AND g.revoked_at IS NULL AND (g.expires_at IS NULL OR g.expires_at > clock_timestamp())
   FOR SHARE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'SESSION_NOT_GRANTED: % has no live % grant on project %', p_author, p_scope, p_project USING ERRCODE = '42501';
  END IF;
END $f$;

-- Idempotency: same request + same payload + same author + same login -> stored response; anything else on the same id -> 23505.
CREATE OR REPLACE FUNCTION ledger._reserve(p_request uuid, p_operation text, p_payload jsonb, p_author text) RETURNS jsonb
LANGUAGE plpgsql SET search_path = ledger, pg_temp AS $f$
DECLARE r ledger.request_receipt; v_sha text;
BEGIN
  IF p_request IS NULL THEN RAISE EXCEPTION 'request id is required' USING ERRCODE = '22023'; END IF;
  v_sha := encode(sha256(convert_to(p_payload::text, 'UTF8')), 'hex');
  INSERT INTO ledger.request_receipt (request_id, operation, payload_sha256, author_id)
  VALUES (p_request, p_operation, v_sha, p_author) ON CONFLICT (request_id) DO NOTHING;
  SELECT * INTO r FROM ledger.request_receipt WHERE request_id = p_request FOR UPDATE;
  IF r.operation <> p_operation OR r.payload_sha256 <> v_sha OR r.author_id <> p_author OR r.login <> session_user THEN
    RAISE EXCEPTION 'request % is already bound to another operation, payload, author or login', p_request USING ERRCODE = '23505';
  END IF;
  RETURN r.response;
END $f$;

CREATE OR REPLACE FUNCTION ledger._finish(p_request uuid, p_response jsonb) RETURNS jsonb
LANGUAGE plpgsql SET search_path = ledger, pg_temp AS $f$
BEGIN
  UPDATE ledger.request_receipt SET response = p_response WHERE request_id = p_request AND response IS NULL;
  IF NOT FOUND THEN RAISE EXCEPTION 'request % missing or already finished', p_request USING ERRCODE = '23514'; END IF;
  RETURN p_response;
END $f$;

CREATE OR REPLACE FUNCTION ledger._hist(p_author text, p_entity text, p_id text, p_op text, p_from text, p_to text,
                             p_changes jsonb, p_reason text, p_evidence_uri text, p_evidence_sha256 text, p_request uuid, p_corrects bigint DEFAULT NULL)
RETURNS bigint LANGUAGE sql SET search_path = ledger, pg_temp AS $f$
  INSERT INTO ledger.history (author_id, actor_assurance, entity, entity_id, op, from_state, to_state, changes, reason, evidence_uri, evidence_sha256, request_id, corrects_id)
  VALUES (p_author, 'DECLARED', p_entity, p_id, p_op, p_from, p_to, coalesce(p_changes, '{}'), p_reason, p_evidence_uri, p_evidence_sha256, p_request, p_corrects)
  RETURNING id
$f$;

-- Coded columns (ledger.common_code_use) hold codes of ledger.common_code. Unknown codes are refused; an inactive code is
-- refused when it is written (insert, or a changed value) and kept on rows that already hold it, except where the
-- column is always_active: then every write of the row needs an active code (defect.severity is a required rating).
-- The error lists the valid codes read from the table.
CREATE OR REPLACE FUNCTION ledger._check_codes() RETURNS trigger LANGUAGE plpgsql SET search_path = ledger, pg_temp AS $f$
DECLARE u record; v text; v_active boolean; v_new jsonb := to_jsonb(NEW); v_old jsonb; v_valid text;
BEGIN
  IF TG_OP = 'UPDATE' THEN v_old := to_jsonb(OLD); END IF;
  FOR u IN SELECT column_name, code_group, always_active FROM ledger.common_code_use WHERE table_name = TG_TABLE_NAME LOOP
    v := v_new->>u.column_name;
    IF v IS NULL AND NOT u.always_active THEN CONTINUE; END IF;
    SELECT active INTO v_active FROM ledger.common_code WHERE code_group = u.code_group AND code = v;
    IF v IS NULL OR v_active IS NULL
       OR (NOT v_active AND (u.always_active OR v_old IS NULL OR v IS DISTINCT FROM v_old->>u.column_name)) THEN
      SELECT string_agg(code, ', ' ORDER BY sort_order, code) INTO v_valid FROM ledger.common_code WHERE code_group = u.code_group AND active;
      IF v IS NULL THEN
        RAISE EXCEPTION 'CODE_REQUIRED: %.% is required, one of: % (group %)', TG_TABLE_NAME, u.column_name, v_valid, u.code_group USING ERRCODE = '23514';
      END IF;
      RAISE EXCEPTION 'CODE_INVALID: %.% = % is not an active code of %, use one of: %; %', TG_TABLE_NAME, u.column_name, v, u.code_group, v_valid,
        CASE WHEN u.always_active AND v_active IS NOT NULL THEN '; this row still holds an old value, set it in the same write' ELSE '' END USING ERRCODE = '23514';
    END IF;
  END LOOP;
  RETURN NEW;
END $f$;
DO $t$
DECLARE t text;
BEGIN
  FOR t IN SELECT DISTINCT table_name FROM ledger.common_code_use LOOP
    EXECUTE format('DROP TRIGGER IF EXISTS %I ON ledger.%I', t || '_codes', t);
    EXECUTE format('CREATE TRIGGER %I BEFORE INSERT OR UPDATE ON ledger.%I FOR EACH ROW EXECUTE FUNCTION ledger._check_codes()', t || '_codes', t);
  END LOOP;
END $t$;

-- Codes are never deleted or renamed (rows keep pointing at them); retire one with active = false.
CREATE OR REPLACE FUNCTION ledger._code_keep() RETURNS trigger LANGUAGE plpgsql AS $f$
BEGIN
  IF TG_OP = 'DELETE' OR NEW.code_group <> OLD.code_group OR NEW.code <> OLD.code THEN
    RAISE EXCEPTION 'common codes are never deleted or renamed (% %/% refused); set active = false instead', TG_OP, OLD.code_group, OLD.code USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END $f$;
DROP TRIGGER IF EXISTS common_code_keep ON ledger.common_code;
CREATE TRIGGER common_code_keep BEFORE UPDATE OR DELETE ON ledger.common_code FOR EACH ROW EXECUTE FUNCTION ledger._code_keep();

CREATE OR REPLACE FUNCTION ledger._check_reason(p_reason text) RETURNS void LANGUAGE plpgsql IMMUTABLE AS $f$
BEGIN
  IF p_reason IS NULL OR length(btrim(p_reason)) NOT BETWEEN 10 AND 1000 THEN
    RAISE EXCEPTION 'reason must be 10..1000 characters' USING ERRCODE = '22023';
  END IF;
END $f$;

-- ---------- session grants (ledger_owner only: an operator runs these as a superuser) ----------
CREATE OR REPLACE FUNCTION ledger.grant_session(p_author text, p_project text, p_scope text, p_expires_at timestamptz DEFAULT NULL, p_note text DEFAULT NULL)
RETURNS bigint LANGUAGE plpgsql SET search_path = ledger, pg_temp AS $f$
DECLARE v_id bigint;
BEGIN
  -- an earlier open (possibly expired) grant for the same key is closed first; rows are never deleted
  UPDATE ledger.session_grant SET revoked_at = clock_timestamp()
   WHERE author_id = p_author AND project = p_project AND scope = p_scope AND revoked_at IS NULL;
  INSERT INTO ledger.session_grant (author_id, project, scope, expires_at, note) VALUES (p_author, p_project, p_scope, p_expires_at, p_note)
  RETURNING id INTO v_id;
  RETURN v_id;
END $f$;
CREATE OR REPLACE FUNCTION ledger.revoke_session(p_author text, p_project text DEFAULT NULL, p_scope text DEFAULT NULL)
RETURNS int LANGUAGE plpgsql SET search_path = ledger, pg_temp AS $f$
DECLARE n int;
BEGIN
  UPDATE ledger.session_grant SET revoked_at = clock_timestamp()
   WHERE author_id = p_author AND (p_project IS NULL OR project = p_project) AND (p_scope IS NULL OR scope = p_scope) AND revoked_at IS NULL;
  GET DIAGNOSTICS n = ROW_COUNT;
  RETURN n;
END $f$;

-- A host attestation is transaction-local; owner SQL without one is stamped user:owner.
CREATE OR REPLACE FUNCTION ledger._stamp_created_by() RETURNS trigger LANGUAGE plpgsql AS $f$
BEGIN
  IF TG_OP='INSERT' THEN
    NEW.created_by := coalesce(nullif(current_setting('ledger.author',true),''),'user:owner');
  ELSE NEW.created_by := OLD.created_by;
  END IF;
  RETURN NEW;
END $f$;
DO $t$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['project','task','defect','condition','defect_task','defect_relation','condition_link','common_code'] LOOP
    EXECUTE format('DROP TRIGGER IF EXISTS %I ON ledger.%I',t || '_created_by',t);
    EXECUTE format('CREATE TRIGGER %I BEFORE INSERT OR UPDATE ON ledger.%I FOR EACH ROW EXECUTE FUNCTION ledger._stamp_created_by()',t || '_created_by',t);
  END LOOP;
END $t$;

-- ---------- public write API ----------
-- save: create (p_expected_version = 0) or edit non-state columns of defect / task / condition.
-- Scope: defect -> 'defect', task / condition -> 'task', on the row's project (and on the new project when a task or
-- condition moves). A new defect needs fields.project_id; when p_id is NULL or '' the next '<code>-D-<n>' id is assigned.
CREATE OR REPLACE FUNCTION ledger.save(p_request uuid, p_author text, p_entity text, p_id text, p_expected_version int, p_fields jsonb, p_reason text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = ledger, pg_temp AS $f$
DECLARE
  prior jsonb; allowed text[]; bad text; cur jsonb; v_new jsonb; changes jsonb := '{}'; k text; v_id text := nullif(p_id, '');
  v_now timestamptz := clock_timestamp(); v_hist bigint; v_ver int; cols text; v_scope text; v_project text; v_code text;
BEGIN
  allowed := CASE p_entity
    WHEN 'defect' THEN ARRAY['project_id','title','category','severity','behavior','root_cause','owner_actor_id']
    WHEN 'task' THEN ARRAY['project_id','title','purpose','success_condition','owner_actor_id','origin_ref','origin_quote']
    WHEN 'condition' THEN ARRAY['project_id','release','goal_label','code','condition_text']
    ELSE NULL END;
  IF allowed IS NULL THEN RAISE EXCEPTION 'save: unknown entity %', p_entity USING ERRCODE = '22023'; END IF;
  IF p_fields IS NULL OR jsonb_typeof(p_fields) <> 'object' OR p_fields = '{}' THEN RAISE EXCEPTION 'save: fields object required' USING ERRCODE = '22023'; END IF;
  SELECT string_agg(x, ',') INTO bad FROM jsonb_object_keys(p_fields) x WHERE x <> ALL (allowed);
  IF bad IS NOT NULL THEN RAISE EXCEPTION 'save: field(s) % not editable here (state changes use transition)', bad USING ERRCODE = '22023'; END IF;
  IF jsonb_typeof(p_fields->'owner_actor_id') = 'string' AND NOT ledger._author_ok(p_fields->>'owner_actor_id') THEN
    RAISE EXCEPTION 'AUTHOR_ID_FORMAT: owner_actor_id must be <vendor>:<session-id> or user:owner (got %)', p_fields->>'owner_actor_id' USING ERRCODE = '22023';
  END IF;
  IF p_expected_version <> 0 THEN
    IF v_id IS NULL THEN RAISE EXCEPTION 'save: id required to edit' USING ERRCODE = '22023'; END IF;
    IF p_entity = 'defect' AND p_fields ? 'project_id' THEN RAISE EXCEPTION 'save: a defect cannot move to another project (its id carries the project code)' USING ERRCODE = '22023'; END IF;
    EXECUTE format('SELECT to_jsonb(t) FROM ledger.%I t WHERE id = $1 FOR UPDATE', p_entity) INTO cur USING v_id;
    IF cur IS NULL THEN RAISE EXCEPTION 'save: % % not found', p_entity, v_id USING ERRCODE = '22023'; END IF;
  END IF;
  v_scope := CASE p_entity WHEN 'defect' THEN 'defect' ELSE 'task' END;
  v_project := coalesce(cur->>'project_id', p_fields->>'project_id');
  IF v_project IS NULL THEN RAISE EXCEPTION 'save: project_id required' USING ERRCODE = '22023'; END IF;
  PERFORM ledger._begin(p_author, v_scope, v_project);
  IF p_fields ? 'project_id' AND p_fields->>'project_id' IS DISTINCT FROM v_project THEN
    PERFORM ledger._begin(p_author, v_scope, p_fields->>'project_id');
  END IF;
  PERFORM ledger._check_reason(p_reason);
  prior := ledger._reserve(p_request, 'save', jsonb_build_object('entity', p_entity, 'id', p_id, 'v', p_expected_version, 'fields', p_fields, 'reason', p_reason), p_author);
  IF prior IS NOT NULL THEN RETURN prior; END IF;

  IF p_expected_version = 0 THEN
    IF p_entity = 'defect' THEN
      SELECT code INTO v_code FROM ledger.project WHERE id = v_project;
      IF v_code IS NULL THEN RAISE EXCEPTION 'save: project % not found', v_project USING ERRCODE = '22023'; END IF;
      IF v_id IS NULL THEN
        PERFORM pg_advisory_xact_lock(hashtext('ledger.defect.id:' || v_code));   -- one new id per project at a time
        SELECT v_code || '-D-' || lpad((coalesce(max(substring(id FROM '-D-([0-9]+)$')::int), 0) + 1)::text, 4, '0')
          INTO v_id FROM ledger.defect WHERE project_id = v_project;
      ELSIF split_part(v_id, '-D-', 1) <> v_code THEN
        RAISE EXCEPTION 'save: defect id % must start with %-D-', v_id, v_code USING ERRCODE = '22023';
      END IF;
    ELSIF v_id IS NULL THEN RAISE EXCEPTION 'save: id required for %', p_entity USING ERRCODE = '22023';
    END IF;
    v_new := p_fields || jsonb_build_object('id', v_id, 'version', 1);
    IF p_entity = 'defect' THEN
      v_new := v_new || jsonb_build_object('status', 'OPEN', 'created_at', v_now, 'updated_at', v_now);
      INSERT INTO ledger.defect SELECT * FROM jsonb_populate_record(NULL::ledger.defect, v_new);
    ELSIF p_entity = 'task' THEN
      v_new := v_new || jsonb_build_object('state', 'PLANNED', 'created_at', v_now, 'updated_at', v_now);
      INSERT INTO ledger.task SELECT * FROM jsonb_populate_record(NULL::ledger.task, v_new);
    ELSE
      v_new := v_new || jsonb_build_object('state', 'UNKNOWN');
      INSERT INTO ledger.condition SELECT * FROM jsonb_populate_record(NULL::ledger.condition, v_new);
    END IF;
    SELECT jsonb_object_agg(key, jsonb_build_array(NULL, value)) INTO changes FROM jsonb_each(p_fields);
    v_hist := ledger._hist(p_author, p_entity, v_id, 'CREATE', NULL, NULL, changes, p_reason, NULL, NULL, p_request);
    v_ver := 1;
  ELSE
    IF (cur->>'version')::int <> p_expected_version THEN
      RAISE EXCEPTION 'version conflict on % %: expected %, current %', p_entity, v_id, p_expected_version, cur->>'version' USING ERRCODE = '40001';
    END IF;
    FOR k IN SELECT jsonb_object_keys(p_fields) LOOP
      IF cur->k IS DISTINCT FROM p_fields->k THEN changes := changes || jsonb_build_object(k, jsonb_build_array(cur->k, p_fields->k)); END IF;
    END LOOP;
    IF changes = '{}' THEN RAISE EXCEPTION 'save: nothing changes' USING ERRCODE = '22023'; END IF;
    SELECT string_agg(format('%I = n.%I', x, x), ', ') INTO cols FROM jsonb_object_keys(changes) x;
    EXECUTE format('UPDATE ledger.%I t SET %s, version = t.version + 1%s FROM jsonb_populate_record(NULL::ledger.%I, $1) n WHERE t.id = $2',
                   p_entity, cols, CASE WHEN p_entity = 'condition' THEN '' ELSE ', updated_at = clock_timestamp()' END, p_entity)
      USING p_fields, v_id;
    v_hist := ledger._hist(p_author, p_entity, v_id, 'EDIT', NULL, NULL, changes, p_reason, NULL, NULL, p_request);
    v_ver := p_expected_version + 1;
  END IF;
  RETURN ledger._finish(p_request, jsonb_build_object('status', 'COMMITTED', 'entity', p_entity, 'id', v_id, 'version', v_ver, 'history_id', v_hist,
                        'author', p_author, 'login', session_user));
END $f$;

-- transition: one state change + one TRANSITION history row.
-- Scope 'verify' (login ledger_reviewer only) for: -> VERIFIED / MET / NOT_MET, and any move out of VERIFIED / MET / NOT_MET.
CREATE OR REPLACE FUNCTION ledger.transition(p_request uuid, p_author text, p_entity text, p_id text, p_expected_version int, p_to text, p_reason text,
                                  p_evidence_uri text DEFAULT NULL, p_evidence_sha256 text DEFAULT NULL, p_merged_into text DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = ledger, pg_temp AS $f$
DECLARE prior jsonb; v_from text; v_ver int; v_owner text; v_project text; v_scope text; v_reporter text; v_hist bigint;
BEGIN
  IF p_entity = 'defect' THEN
    SELECT status, version, project_id INTO v_from, v_ver, v_project FROM ledger.defect WHERE id = p_id FOR UPDATE;
  ELSIF p_entity = 'task' THEN
    SELECT state, version, owner_actor_id, project_id INTO v_from, v_ver, v_owner, v_project FROM ledger.task WHERE id = p_id FOR UPDATE;
  ELSIF p_entity = 'condition' THEN
    SELECT state, version, project_id INTO v_from, v_ver, v_project FROM ledger.condition WHERE id = p_id FOR UPDATE;
  ELSE RAISE EXCEPTION 'transition: unknown entity %', p_entity USING ERRCODE = '22023';
  END IF;
  IF v_from IS NULL THEN RAISE EXCEPTION 'transition: % % not found', p_entity, p_id USING ERRCODE = '22023'; END IF;
  v_scope := CASE WHEN p_to IN ('VERIFIED','MET','NOT_MET') OR v_from IN ('VERIFIED','MET','NOT_MET') THEN 'verify'
                  WHEN p_entity = 'defect' THEN 'defect' ELSE 'task' END;
  PERFORM ledger._begin(p_author, v_scope, v_project);
  PERFORM ledger._check_reason(p_reason);
  prior := ledger._reserve(p_request, 'transition', jsonb_build_object('entity', p_entity, 'id', p_id, 'v', p_expected_version, 'to', p_to, 'reason', p_reason,
                           'evidence_uri', p_evidence_uri, 'evidence_sha256', p_evidence_sha256, 'merged_into', p_merged_into), p_author);
  IF prior IS NOT NULL THEN RETURN prior; END IF;
  IF v_ver <> p_expected_version THEN
    RAISE EXCEPTION 'version conflict on % %: expected %, current %', p_entity, p_id, p_expected_version, v_ver USING ERRCODE = '40001';
  END IF;
  IF v_from = p_to THEN RAISE EXCEPTION 'transition: already %', p_to USING ERRCODE = '22023'; END IF;

  IF p_entity = 'defect' THEN
    IF p_to = 'SOURCE_FIXED' AND NOT EXISTS (SELECT 1 FROM ledger.defect_fix WHERE defect_id = p_id) THEN
      RAISE EXCEPTION 'SOURCE_FIXED needs a recorded fix commit for %', p_id USING ERRCODE = '23514';
    END IF;
    IF v_from = 'VERIFIED' AND p_evidence_uri IS NULL THEN
      RAISE EXCEPTION 'leaving VERIFIED needs evidence_uri' USING ERRCODE = '23514';
    END IF;
    IF p_to = 'VERIFIED' THEN
      IF v_from <> 'SOURCE_FIXED' THEN RAISE EXCEPTION 'VERIFIED only from SOURCE_FIXED (now %)', v_from USING ERRCODE = '23514'; END IF;
      IF p_evidence_uri IS NULL THEN RAISE EXCEPTION 'VERIFIED needs evidence_uri' USING ERRCODE = '23514'; END IF;
      IF NOT EXISTS (SELECT 1 FROM ledger.defect_fix WHERE defect_id = p_id) THEN
        RAISE EXCEPTION 'VERIFIED needs a recorded fix commit' USING ERRCODE = '23514';
      END IF;
      IF NOT EXISTS (SELECT 1 FROM ledger.defect_fix WHERE defect_id = p_id AND ledger._sid(recorded_by) <> ledger._sid(p_author)) THEN
        RAISE EXCEPTION 'SAME_SESSION: the reviewing session must differ from the session that recorded the fix' USING ERRCODE = '23514';
      END IF;
    END IF;
    IF p_to IN ('NOT_DEFECT','DISPUTED') AND p_evidence_uri IS NULL THEN
      RAISE EXCEPTION '% needs evidence_uri', p_to USING ERRCODE = '23514';
    END IF;
    IF p_to = 'MERGED' THEN
      IF p_merged_into IS NULL OR p_merged_into = p_id
         OR NOT EXISTS (SELECT 1 FROM ledger.defect WHERE id = p_merged_into AND status <> 'MERGED') THEN
        RAISE EXCEPTION 'MERGED needs a live merge target' USING ERRCODE = '23514';
      END IF;
    END IF;
    UPDATE ledger.defect SET status = p_to, merged_into = CASE WHEN p_to = 'MERGED' THEN p_merged_into END,
           version = version + 1, updated_at = clock_timestamp() WHERE id = p_id;
  ELSIF p_entity = 'task' THEN
    IF p_to = 'UNKNOWN' THEN RAISE EXCEPTION 'task cannot move to UNKNOWN' USING ERRCODE = '23514'; END IF;
    IF p_to = 'REPORTED_DONE' AND p_evidence_uri IS NULL THEN RAISE EXCEPTION 'REPORTED_DONE needs evidence_uri' USING ERRCODE = '23514'; END IF;
    IF v_from = 'VERIFIED' AND p_evidence_uri IS NULL THEN RAISE EXCEPTION 'leaving VERIFIED needs evidence_uri' USING ERRCODE = '23514'; END IF;
    IF p_to = 'VERIFIED' THEN
      IF v_from <> 'REPORTED_DONE' THEN RAISE EXCEPTION 'VERIFIED only from REPORTED_DONE (now %)', v_from USING ERRCODE = '23514'; END IF;
      IF p_evidence_uri IS NULL THEN RAISE EXCEPTION 'VERIFIED needs evidence_uri' USING ERRCODE = '23514'; END IF;
      IF ledger._sid(v_owner) = ledger._sid(p_author) THEN RAISE EXCEPTION 'SAME_SESSION: the task owner cannot verify it' USING ERRCODE = '23514'; END IF;
      SELECT h.author_id INTO v_reporter FROM ledger.history h
       WHERE h.entity = 'task' AND h.entity_id = p_id AND h.op = 'TRANSITION' AND h.to_state = 'REPORTED_DONE' ORDER BY h.id DESC LIMIT 1;
      IF ledger._sid(v_reporter) = ledger._sid(p_author) THEN
        RAISE EXCEPTION 'SAME_SESSION: the session that reported the task done cannot verify it' USING ERRCODE = '23514';
      END IF;
    END IF;
    UPDATE ledger.task SET state = p_to, version = version + 1, updated_at = clock_timestamp() WHERE id = p_id;
  ELSE
    IF p_to IN ('MET','NOT_MET') AND p_evidence_uri IS NULL THEN RAISE EXCEPTION '% needs evidence_uri', p_to USING ERRCODE = '23514'; END IF;
    UPDATE ledger.condition SET state = p_to, version = version + 1 WHERE id = p_id;
  END IF;
  v_hist := ledger._hist(p_author, p_entity, p_id, 'TRANSITION', v_from, p_to,
                         CASE WHEN p_to = 'MERGED' THEN jsonb_build_object('merged_into', jsonb_build_array(NULL, p_merged_into)) END,
                         p_reason, p_evidence_uri, p_evidence_sha256, p_request);
  RETURN ledger._finish(p_request, jsonb_build_object('status', 'COMMITTED', 'entity', p_entity, 'id', p_id, 'from', v_from, 'to', p_to,
                        'version', v_ver + 1, 'history_id', v_hist, 'author', p_author, 'login', session_user));
END $f$;

-- record_fix (scope 'fix' on the defect's project): add (or update CI of) a fix commit; an OPEN defect moves to SOURCE_FIXED
-- in the same transaction unless p_mark_source_fixed = false.
CREATE OR REPLACE FUNCTION ledger.record_fix(p_request uuid, p_author text, p_defect text, p_repo text, p_commit text, p_reason text,
                                  p_branch text DEFAULT NULL, p_ci_ref text DEFAULT NULL, p_ci_conclusion text DEFAULT NULL,
                                  p_ci_completed_at timestamptz DEFAULT NULL, p_mark_source_fixed boolean DEFAULT true)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = ledger, pg_temp AS $f$
DECLARE prior jsonb; v_status text; v_ver int; v_project text; old_fix ledger.defect_fix; changes jsonb := '{}'; v_hist bigint; v_trans bigint; v_fix bigint;
BEGIN
  -- Lock the defect first: the SOURCE_FIXED check and the fix insert then see the same state (no write skew).
  SELECT status, version, project_id INTO v_status, v_ver, v_project FROM ledger.defect WHERE id = p_defect FOR UPDATE;
  IF v_status IS NULL THEN RAISE EXCEPTION 'record_fix: defect % not found', p_defect USING ERRCODE = '22023'; END IF;
  PERFORM ledger._begin(p_author, 'fix', v_project);
  PERFORM ledger._check_reason(p_reason);
  prior := ledger._reserve(p_request, 'record_fix', jsonb_build_object('defect', p_defect, 'repo', p_repo, 'commit', p_commit, 'branch', p_branch, 'ci_ref', p_ci_ref,
                           'ci_conclusion', p_ci_conclusion, 'ci_completed_at', p_ci_completed_at, 'mark', p_mark_source_fixed, 'reason', p_reason), p_author);
  IF prior IS NOT NULL THEN RETURN prior; END IF;
  SELECT * INTO old_fix FROM ledger.defect_fix WHERE defect_id = p_defect AND repo = p_repo AND commit_sha = p_commit;
  IF old_fix.id IS NULL THEN
    INSERT INTO ledger.defect_fix (defect_id, repo, commit_sha, branch, ci_ref, ci_conclusion, ci_completed_at, recorded_by, recorded_at)
    VALUES (p_defect, p_repo, p_commit, p_branch, p_ci_ref, p_ci_conclusion, p_ci_completed_at, p_author, clock_timestamp()) RETURNING id INTO v_fix;
    v_hist := ledger._hist(p_author, 'defect_fix', p_defect || '@' || p_commit, 'CREATE', NULL, NULL,
                jsonb_build_object('repo', jsonb_build_array(NULL, p_repo), 'branch', jsonb_build_array(NULL, p_branch), 'ci_ref', jsonb_build_array(NULL, p_ci_ref), 'ci_conclusion', jsonb_build_array(NULL, p_ci_conclusion)),
                p_reason, p_ci_ref, NULL, p_request);
  ELSE
    IF p_ci_ref IS NULL THEN RAISE EXCEPTION 'record_fix: fix already recorded; pass CI data to update it' USING ERRCODE = '22023'; END IF;
    IF old_fix.ci_ref IS DISTINCT FROM p_ci_ref THEN changes := changes || jsonb_build_object('ci_ref', jsonb_build_array(old_fix.ci_ref, p_ci_ref)); END IF;
    IF old_fix.ci_conclusion IS DISTINCT FROM p_ci_conclusion THEN changes := changes || jsonb_build_object('ci_conclusion', jsonb_build_array(old_fix.ci_conclusion, p_ci_conclusion)); END IF;
    IF old_fix.ci_completed_at IS DISTINCT FROM p_ci_completed_at THEN changes := changes || jsonb_build_object('ci_completed_at', jsonb_build_array(old_fix.ci_completed_at, p_ci_completed_at)); END IF;
    IF changes = '{}' THEN RAISE EXCEPTION 'record_fix: nothing changes' USING ERRCODE = '22023'; END IF;
    UPDATE ledger.defect_fix SET ci_ref = p_ci_ref, ci_conclusion = p_ci_conclusion, ci_completed_at = p_ci_completed_at WHERE id = old_fix.id;
    v_fix := old_fix.id;
    v_hist := ledger._hist(p_author, 'defect_fix', p_defect || '@' || p_commit, 'EDIT', NULL, NULL, changes, p_reason, p_ci_ref, NULL, p_request);
  END IF;
  IF v_status = 'OPEN' AND p_mark_source_fixed THEN
    UPDATE ledger.defect SET status = 'SOURCE_FIXED', version = version + 1, updated_at = clock_timestamp() WHERE id = p_defect;
    v_trans := ledger._hist(p_author, 'defect', p_defect, 'TRANSITION', 'OPEN', 'SOURCE_FIXED',
                 jsonb_build_object('fix', jsonb_build_array(NULL, p_commit)), p_reason, p_ci_ref, NULL, p_request);
    v_status := 'SOURCE_FIXED'; v_ver := v_ver + 1;
  END IF;
  RETURN ledger._finish(p_request, jsonb_build_object('status', 'COMMITTED', 'defect', p_defect, 'fix_id', v_fix, 'defect_status', v_status,
                        'version', v_ver, 'history_id', v_hist, 'transition_history_id', v_trans, 'author', p_author, 'login', session_user));
END $f$;

-- link: defect_task | relation kinds (same_site, caused_by, blocks, same_family, same_fix_unit) -> scope 'defect' on the
-- project(s) of the defect(s); condition_task | condition_defect -> scope 'task' on the condition's project.
CREATE OR REPLACE FUNCTION ledger.link(p_request uuid, p_author text, p_kind text, p_a text, p_b text, p_reason text, p_remove boolean DEFAULT false)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = ledger, pg_temp AS $f$
DECLARE prior jsonb; n int; v_entity text; v_id text; x text; y text; v_hist bigint; v_project text; v_project_b text;
BEGIN
  IF p_kind IN ('condition_task','condition_defect') THEN
    SELECT project_id INTO v_project FROM ledger.condition WHERE id = p_a;
    IF v_project IS NULL THEN RAISE EXCEPTION 'link: condition % not found', p_a USING ERRCODE = '22023'; END IF;
    PERFORM ledger._begin(p_author, 'task', v_project);
  ELSIF p_kind = 'defect_task' OR EXISTS (SELECT 1 FROM ledger.common_code WHERE code_group = 'RELATION_KIND' AND code = p_kind) THEN
    v_project := ledger._defect_project(p_a);
    IF v_project IS NULL THEN RAISE EXCEPTION 'link: defect % not found', p_a USING ERRCODE = '22023'; END IF;
    PERFORM ledger._begin(p_author, 'defect', v_project);
    IF p_kind <> 'defect_task' THEN
      v_project_b := ledger._defect_project(p_b);
      IF v_project_b IS NULL THEN RAISE EXCEPTION 'link: defect % not found', p_b USING ERRCODE = '22023'; END IF;
      IF v_project_b <> v_project THEN PERFORM ledger._begin(p_author, 'defect', v_project_b); END IF;
    END IF;
  ELSE
    RAISE EXCEPTION 'link: unknown kind %, use defect_task, condition_task, condition_defect or a RELATION_KIND code: %', p_kind,
      (SELECT string_agg(code, ', ' ORDER BY sort_order, code) FROM ledger.common_code WHERE code_group = 'RELATION_KIND' AND active) USING ERRCODE = '22023';
  END IF;
  PERFORM ledger._check_reason(p_reason);
  prior := ledger._reserve(p_request, 'link', jsonb_build_object('kind', p_kind, 'a', p_a, 'b', p_b, 'remove', p_remove, 'reason', p_reason), p_author);
  IF prior IS NOT NULL THEN RETURN prior; END IF;
  IF p_kind = 'defect_task' THEN
    v_entity := 'defect_task'; v_id := p_a || '|' || p_b;
    IF p_remove THEN DELETE FROM ledger.defect_task WHERE defect_id = p_a AND task_id = p_b;
    ELSE INSERT INTO ledger.defect_task (defect_id,task_id) VALUES (p_a, p_b) ON CONFLICT DO NOTHING; END IF;
  ELSIF p_kind IN ('condition_task','condition_defect') THEN
    v_entity := 'condition_link'; v_id := p_a || '|' || p_b;
    IF p_remove THEN
      DELETE FROM ledger.condition_link WHERE condition_id = p_a AND (CASE WHEN p_kind = 'condition_task' THEN task_id ELSE defect_id END) = p_b;
    ELSIF p_kind = 'condition_task' THEN INSERT INTO ledger.condition_link (condition_id, task_id) VALUES (p_a, p_b) ON CONFLICT DO NOTHING;
    ELSE INSERT INTO ledger.condition_link (condition_id, defect_id) VALUES (p_a, p_b) ON CONFLICT DO NOTHING; END IF;
  ELSE
    x := least(p_a, p_b); y := greatest(p_a, p_b);
    v_entity := 'defect_relation'; v_id := x || '|' || y || '|' || p_kind;
    IF p_remove THEN DELETE FROM ledger.defect_relation WHERE a = x AND b = y AND kind = p_kind;
    ELSE INSERT INTO ledger.defect_relation (a,b,kind) VALUES (x, y, p_kind) ON CONFLICT DO NOTHING; END IF;
  END IF;
  GET DIAGNOSTICS n = ROW_COUNT;
  IF n <> 1 THEN RAISE EXCEPTION 'link: nothing changed (% % %)', p_kind, p_a, p_b USING ERRCODE = '22023'; END IF;
  v_hist := ledger._hist(p_author, v_entity, v_id, CASE WHEN p_remove THEN 'UNLINK' ELSE 'LINK' END, NULL, NULL,
                         jsonb_build_object('kind', p_kind), p_reason, NULL, NULL, p_request);
  RETURN ledger._finish(p_request, jsonb_build_object('status', 'COMMITTED', 'entity', v_entity, 'id', v_id, 'removed', p_remove, 'history_id', v_hist,
                        'author', p_author, 'login', session_user));
END $f$;

-- record_case (scope 'defect' on the defect's project): add one observed case under a defect. A MERGED defect is refused
-- with its merge target named, so the cases of one root cause stay on the live defect. The defect row is locked FOR SHARE:
-- a concurrent transition to MERGED (FOR UPDATE) either commits first, and this call sees MERGED, or waits for it.
CREATE OR REPLACE FUNCTION ledger.record_case(p_request uuid, p_author text, p_defect text, p_observation text, p_reason text,
                                   p_evidence text DEFAULT NULL, p_user_quote text DEFAULT NULL, p_observed_at timestamptz DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = ledger, pg_temp AS $f$
DECLARE prior jsonb; v_status text; v_into text; v_project text; v_case bigint; v_id text; v_hist bigint;
BEGIN
  SELECT status, merged_into, project_id INTO v_status, v_into, v_project FROM ledger.defect WHERE id = p_defect FOR SHARE;
  IF v_status IS NULL THEN RAISE EXCEPTION 'record_case: defect % not found', p_defect USING ERRCODE = '22023'; END IF;
  PERFORM ledger._begin(p_author, 'defect', v_project);
  PERFORM ledger._check_reason(p_reason);
  prior := ledger._reserve(p_request, 'record_case', jsonb_build_object('defect', p_defect, 'observation', p_observation, 'evidence', p_evidence,
                           'user_quote', p_user_quote, 'observed_at', p_observed_at, 'reason', p_reason), p_author);
  IF prior IS NOT NULL THEN RETURN prior; END IF;
  IF v_status = 'MERGED' THEN
    RAISE EXCEPTION 'MERGED_DEFECT: % was merged into %; record the case on %', p_defect, v_into, v_into USING ERRCODE = '23514';
  END IF;
  INSERT INTO ledger.defect_case (defect_id, observation, evidence, user_quote, observed_at, recorded_by, recorded_at)
  VALUES (p_defect, p_observation, p_evidence, p_user_quote, p_observed_at, p_author, clock_timestamp()) RETURNING id INTO v_case;
  v_id := p_defect || '|' || v_case;   -- '<defect>|<case>': correct() finds the project from the part before '|'
  v_hist := ledger._hist(p_author, 'defect_case', v_id, 'CREATE', NULL, NULL,
              jsonb_build_object('observation', jsonb_build_array(NULL, p_observation), 'evidence', jsonb_build_array(NULL, p_evidence),
                                 'user_quote', jsonb_build_array(NULL, p_user_quote), 'observed_at', jsonb_build_array(NULL, p_observed_at)),
              p_reason, NULL, NULL, p_request);
  RETURN ledger._finish(p_request, jsonb_build_object('status', 'COMMITTED', 'entity', 'defect_case', 'id', v_id, 'case_id', v_case, 'defect', p_defect,
                        'history_id', v_hist, 'author', p_author, 'login', session_user));
END $f$;

-- correct: point at a wrong history row with a CORRECT row; the history row itself is never changed.
-- Scope = the scope that writes the corrected entity ('verify' when the corrected row is a verification).
CREATE OR REPLACE FUNCTION ledger.correct(p_request uuid, p_author text, p_history_id bigint, p_reason text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = ledger, pg_temp AS $f$
DECLARE prior jsonb; h ledger.history; v_hist bigint; v_scope text; v_project text;
BEGIN
  SELECT * INTO h FROM ledger.history WHERE id = p_history_id;
  IF h.id IS NULL THEN RAISE EXCEPTION 'correct: history % not found', p_history_id USING ERRCODE = '22023'; END IF;
  IF h.entity IN ('task','condition','condition_link') THEN
    v_scope := 'task';
    v_project := CASE h.entity WHEN 'task' THEN (SELECT project_id FROM ledger.task WHERE id = h.entity_id)
                               WHEN 'condition' THEN (SELECT project_id FROM ledger.condition WHERE id = h.entity_id)
                               ELSE (SELECT project_id FROM ledger.condition WHERE id = split_part(h.entity_id, '|', 1)) END;
  ELSE
    v_scope := CASE WHEN h.entity = 'defect_fix' THEN 'fix' ELSE 'defect' END;   -- defect, defect_task, defect_relation, defect_case
    v_project := ledger._defect_project(CASE h.entity WHEN 'defect' THEN h.entity_id
                                                      WHEN 'defect_fix' THEN split_part(h.entity_id, '@', 1)
                                                      ELSE split_part(h.entity_id, '|', 1) END);
  END IF;
  IF h.op = 'TRANSITION' AND (h.to_state IN ('VERIFIED','MET','NOT_MET') OR h.from_state IN ('VERIFIED','MET','NOT_MET')) THEN v_scope := 'verify'; END IF;
  PERFORM ledger._begin(p_author, v_scope, v_project);
  PERFORM ledger._check_reason(p_reason);
  prior := ledger._reserve(p_request, 'correct', jsonb_build_object('history_id', p_history_id, 'reason', p_reason), p_author);
  IF prior IS NOT NULL THEN RETURN prior; END IF;
  v_hist := ledger._hist(p_author, h.entity, h.entity_id, 'CORRECT', NULL, NULL, '{}', p_reason, NULL, NULL, p_request, p_history_id);
  RETURN ledger._finish(p_request, jsonb_build_object('status', 'COMMITTED', 'corrects', p_history_id, 'history_id', v_hist, 'author', p_author, 'login', session_user));
END $f$;

-- batch: up to 200 write operations in one transaction; they all commit or none does. Each operation is an object
-- {"op": save | transition | record_fix | record_case | link | correct, <the same arguments as the single function>, "request_id"?}.
-- An operation without request_id gets one derived from the batch request id and its position, so sending the same
-- batch again with the same request id returns the stored results instead of writing twice. SECURITY INVOKER: every
-- operation runs through its public function with that function's login, grant, reason and idempotency checks.
CREATE OR REPLACE FUNCTION ledger.batch(p_request uuid, p_author text, p_ops jsonb) RETURNS jsonb
LANGUAGE plpgsql SET search_path = ledger, pg_temp AS $f$
DECLARE o jsonb; i int; n int; v_op text; v_req uuid; v_res jsonb; results jsonb := '[]'; allowed text[]; required text[]; bad text;
  v_state text; v_msg text;
BEGIN
  IF p_request IS NULL THEN RAISE EXCEPTION 'request id is required' USING ERRCODE = '22023'; END IF;
  IF p_ops IS NULL OR jsonb_typeof(p_ops) <> 'array' THEN RAISE EXCEPTION 'batch: ops must be a JSON array' USING ERRCODE = '22023'; END IF;
  n := jsonb_array_length(p_ops);
  IF n NOT BETWEEN 1 AND 200 THEN RAISE EXCEPTION 'batch: 1..200 operations allowed (got %)', n USING ERRCODE = '22023'; END IF;
  FOR i IN 0 .. n - 1 LOOP
    o := p_ops->i; v_op := CASE WHEN jsonb_typeof(o) = 'object' THEN o->>'op' END;
    BEGIN
      required := CASE v_op
        WHEN 'save' THEN ARRAY['entity','fields','reason']
        WHEN 'transition' THEN ARRAY['entity','id','expected_version','to','reason']
        WHEN 'record_fix' THEN ARRAY['defect_id','repo','commit_sha','reason']
        WHEN 'record_case' THEN ARRAY['defect_id','observation','reason']
        WHEN 'link' THEN ARRAY['kind','a','b','reason']
        WHEN 'correct' THEN ARRAY['history_id','reason'] END;
      IF required IS NULL THEN
        RAISE EXCEPTION 'op must be one of save, transition, record_fix, record_case, link, correct (got %)', coalesce(v_op, 'NULL') USING ERRCODE = '22023';
      END IF;
      allowed := required || ARRAY['op','request_id'] || CASE v_op
        WHEN 'save' THEN ARRAY['id','expected_version']
        WHEN 'transition' THEN ARRAY['evidence_uri','evidence_sha256','merged_into']
        WHEN 'record_fix' THEN ARRAY['branch','ci_ref','ci_conclusion','ci_completed_at','mark_source_fixed']
        WHEN 'record_case' THEN ARRAY['evidence','user_quote','observed_at']
        WHEN 'link' THEN ARRAY['remove']
        ELSE '{}'::text[] END;
      SELECT string_agg(k, ',') INTO bad FROM jsonb_object_keys(o) k WHERE k <> ALL (allowed);
      IF bad IS NOT NULL THEN RAISE EXCEPTION 'unknown key(s) %', bad USING ERRCODE = '22023'; END IF;
      SELECT string_agg(k, ',') INTO bad FROM unnest(required) k WHERE jsonb_typeof(o->k) IS NULL OR jsonb_typeof(o->k) = 'null';
      IF bad IS NOT NULL THEN RAISE EXCEPTION 'missing %', bad USING ERRCODE = '22023'; END IF;
      v_req := coalesce((o->>'request_id')::uuid, md5(p_request::text || ':' || i)::uuid);
      v_res := CASE v_op
        WHEN 'save' THEN ledger.save(v_req, p_author, o->>'entity', o->>'id', coalesce((o->>'expected_version')::int, 0), o->'fields', o->>'reason')
        WHEN 'transition' THEN ledger.transition(v_req, p_author, o->>'entity', o->>'id', (o->>'expected_version')::int, o->>'to', o->>'reason',
                                                 o->>'evidence_uri', o->>'evidence_sha256', o->>'merged_into')
        WHEN 'record_fix' THEN ledger.record_fix(v_req, p_author, o->>'defect_id', o->>'repo', o->>'commit_sha', o->>'reason', o->>'branch',
                                                 o->>'ci_ref', o->>'ci_conclusion', (o->>'ci_completed_at')::timestamptz,
                                                 coalesce((o->>'mark_source_fixed')::boolean, true))
        WHEN 'record_case' THEN ledger.record_case(v_req, p_author, o->>'defect_id', o->>'observation', o->>'reason', o->>'evidence',
                                                   o->>'user_quote', (o->>'observed_at')::timestamptz)
        WHEN 'link' THEN ledger.link(v_req, p_author, o->>'kind', o->>'a', o->>'b', o->>'reason', coalesce((o->>'remove')::boolean, false))
        ELSE ledger.correct(v_req, p_author, (o->>'history_id')::bigint, o->>'reason') END;
    EXCEPTION WHEN OTHERS THEN
      -- re-raised outside the operation: the whole statement, and so every earlier operation, rolls back
      GET STACKED DIAGNOSTICS v_state = RETURNED_SQLSTATE, v_msg = MESSAGE_TEXT;
      RAISE EXCEPTION 'batch op % (%): %; nothing was written', i + 1, coalesce(v_op, '?'), v_msg USING ERRCODE = v_state;
    END;
    results := results || jsonb_build_array(v_res);
  END LOOP;
  RETURN jsonb_build_object('status', 'COMMITTED', 'count', n, 'results', results);
END $f$;

-- whoami: what this login and a declared author may do right now (read only).
CREATE OR REPLACE FUNCTION ledger.whoami(p_author text DEFAULT NULL) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = ledger, pg_temp AS $f$
BEGIN
  IF p_author IS NULL THEN
    RAISE EXCEPTION 'HOST_ATTESTATION_REQUIRED: 호스트 훅이 세션을 넣지 않았다(pg-ledger-mcp 클라이언트 훅 설치 필요)' USING ERRCODE = '22023';
  END IF;
  RETURN jsonb_build_object('session_user', session_user, 'author', p_author, 'author_format_ok', ledger._author_ok(p_author),
         'can_verify_login', session_user = 'ledger_reviewer',
         'grants', coalesce((SELECT jsonb_agg(jsonb_build_object('project', g.project, 'scope', g.scope, 'expires_at', g.expires_at) ORDER BY g.project, g.scope)
                               FROM ledger.session_grant g WHERE g.author_id = p_author AND g.revoked_at IS NULL
                                AND (g.expires_at IS NULL OR g.expires_at > clock_timestamp())), '[]'));
END $f$;

DROP FUNCTION IF EXISTS ledger.codes(text);

-- ---------- views ----------
DROP VIEW IF EXISTS ledger.v_defect, ledger.v_history, ledger.v_attention;
CREATE VIEW ledger.v_defect AS
SELECT d.id, d.project_id, d.title, d.category, d.severity, d.status, d.merged_into, d.owner_actor_id, d.version, d.updated_at, d.created_by,
       h.at AS last_change_at, h.author_id AS last_author, h.login AS last_login, h.actor_assurance AS last_assurance, h.op AS last_op, h.reason AS last_reason,
       COALESCE((SELECT jsonb_agg(jsonb_build_object('commit', f.commit_sha, 'repo', f.repo, 'branch', f.branch, 'ci', f.ci_ref,
                                                     'ci_conclusion', f.ci_conclusion, 'by', f.recorded_by) ORDER BY f.id)
                 FROM ledger.defect_fix f WHERE f.defect_id = d.id), '[]') AS fixes,
       COALESCE((SELECT jsonb_agg(jsonb_build_object('task', t.id, 'state', t.state, 'owner', t.owner_actor_id) ORDER BY t.id)
                 FROM ledger.defect_task dt JOIN ledger.task t ON t.id = dt.task_id WHERE dt.defect_id = d.id), '[]') AS tasks,
       COALESCE((SELECT jsonb_agg(cl.condition_id ORDER BY cl.condition_id) FROM ledger.condition_link cl WHERE cl.defect_id = d.id), '[]') AS conditions,
       -- cases of this defect and of every defect merged into it (also through chains of merges). A merged defect's own
       -- behavior is listed as a case with source 'merged_behavior', so merging needs no copying of text.
       COALESCE((WITH RECURSIVE src(id) AS (SELECT d.id UNION SELECT m.id FROM ledger.defect m JOIN src ON m.merged_into = src.id)
                 SELECT jsonb_agg(jsonb_strip_nulls(to_jsonb(c)) ORDER BY coalesce(c.observed_at, c.at), c.case_id NULLS FIRST, c.defect)
                 FROM (SELECT 'case' AS source, k.defect_id AS defect, k.id AS case_id, k.observation, k.evidence, k.user_quote, k.observed_at,
                              k.recorded_by AS by, k.recorded_at AS at
                         FROM ledger.defect_case k JOIN src ON src.id = k.defect_id
                       UNION ALL
                       SELECT 'merged_behavior', m.id, NULL, m.behavior, NULL, NULL, NULL, NULL, m.created_at
                         FROM ledger.defect m JOIN src ON src.id = m.id WHERE m.id <> d.id) c), '[]') AS cases
FROM ledger.defect d
LEFT JOIN LATERAL (SELECT x.* FROM ledger.history x WHERE x.entity = 'defect' AND x.entity_id = d.id AND x.op <> 'IMPORT'
                   ORDER BY x.at DESC, x.id DESC LIMIT 1) h ON true;

-- vendor = the part before ':' of the author id (imported 'agent:<vendor>:<id>' ids are read the same way)
CREATE VIEW ledger.v_history AS
SELECT h.id, h.at, h.author_id, substring(ledger._sid(h.author_id) FROM '^([A-Za-z0-9_-]+):') AS vendor, h.login, h.actor_assurance,
       h.entity, h.entity_id, h.op, h.from_state, h.to_state, h.changes, h.reason, h.evidence_uri, h.corrects_id, h.request_id, h.legacy_ref
FROM ledger.history h;

CREATE VIEW ledger.v_attention AS
SELECT 'OPEN_WITH_FIX'::text AS kind, d.id, d.project_id FROM ledger.defect d WHERE d.status = 'OPEN' AND EXISTS (SELECT 1 FROM ledger.defect_fix f WHERE f.defect_id = d.id)
UNION ALL SELECT 'FIXED_WITHOUT_COMMIT', d.id, d.project_id FROM ledger.defect d WHERE d.status IN ('SOURCE_FIXED','VERIFIED') AND NOT EXISTS (SELECT 1 FROM ledger.defect_fix f WHERE f.defect_id = d.id)
UNION ALL SELECT 'OPEN_NO_OWNER', d.id, d.project_id FROM ledger.defect d WHERE d.status = 'OPEN' AND d.owner_actor_id IS NULL
UNION ALL SELECT 'TASK_UNKNOWN', t.id, t.project_id FROM ledger.task t WHERE t.state = 'UNKNOWN'
UNION ALL SELECT 'DONE_NOT_VERIFIED', t.id, t.project_id FROM ledger.task t WHERE t.state = 'REPORTED_DONE';

RESET ROLE;

-- ---------- grants ----------
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA ledger FROM PUBLIC;
GRANT USAGE ON SCHEMA ledger TO ledger_author, ledger_reviewer, ledger_reader;
GRANT EXECUTE ON FUNCTION ledger.save(uuid,text,text,text,int,jsonb,text),
                          ledger.record_fix(uuid,text,text,text,text,text,text,text,text,timestamptz,boolean),
                          ledger.record_case(uuid,text,text,text,text,text,text,timestamptz),
                          ledger.link(uuid,text,text,text,text,text,boolean),
                          ledger.batch(uuid,text,jsonb) TO ledger_author;
GRANT EXECUTE ON FUNCTION ledger.transition(uuid,text,text,text,int,text,text,text,text,text),
                          ledger.correct(uuid,text,bigint,text) TO ledger_author, ledger_reviewer;
GRANT EXECUTE ON FUNCTION ledger.whoami(text), ledger._sid(text), ledger._author_ok(text) TO ledger_author, ledger_reviewer, ledger_reader;
GRANT SELECT ON ledger.project, ledger.category, ledger.common_code, ledger.common_code_use, ledger.task, ledger.defect, ledger.defect_fix, ledger.defect_case, ledger.defect_task,
                ledger.defect_relation, ledger.condition, ledger.condition_link, ledger.session_grant, ledger.history, ledger.request_receipt,
                ledger.v_defect, ledger.v_history, ledger.v_attention TO ledger_author, ledger_reviewer, ledger_reader;
INSERT INTO ledger._migration (step, detail) VALUES ('api', jsonb_build_object('by', session_user))
  ON CONFLICT (step) DO UPDATE SET done_at = clock_timestamp(), detail = EXCLUDED.detail;
\if :{?ledger_install_transaction}
\else
COMMIT;
\endif
