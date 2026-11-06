-- 10_tables.sql : roles, schema "ledger" and its tables. Fresh install only (fails if schema ledger exists).
-- Run as a superuser in the target database:  psql -v ON_ERROR_STOP=1 -d <db> -f sql/10_tables.sql
-- Then apply sql/15_defect_case.sql (later tables, also run on existing installs) and sql/20_api.sql (functions, views,
-- grants). deploy/install.sh does all three.
--
-- Accounts are per role, never per vendor or session:
--   ledger_owner    NOLOGIN  owns every object; session grants are written only through it (run as superuser)
--   ledger_author   LOGIN    save / transition / record_fix / link / correct, SELECT
--   ledger_reviewer LOGIN    transition / correct, SELECT; the only login that can verify
--   ledger_reader   LOGIN    SELECT only, default_transaction_read_only
-- Who made a write is the declared author id '<vendor>:<session-id>' (or 'user:owner'), accepted only when
-- ledger.session_grant holds a live grant for that author, project and scope.
\set ON_ERROR_STOP on
BEGIN;
DO $$BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='ledger_owner')    THEN CREATE ROLE ledger_owner NOLOGIN; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='ledger_author')   THEN CREATE ROLE ledger_author LOGIN; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='ledger_reviewer') THEN CREATE ROLE ledger_reviewer LOGIN; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='ledger_reader')   THEN CREATE ROLE ledger_reader LOGIN; END IF;
END$$;
ALTER ROLE ledger_reader SET default_transaction_read_only = on;
-- Databases that revoke CONNECT from PUBLIC need it per login.
DO $$BEGIN EXECUTE format('GRANT CONNECT ON DATABASE %I TO ledger_author, ledger_reviewer, ledger_reader', current_database()); END$$;

CREATE SCHEMA ledger AUTHORIZATION ledger_owner;
REVOKE ALL ON SCHEMA ledger FROM PUBLIC;
SET ROLE ledger_owner;

-- Author id format, used by CHECKs and by the write functions. Any vendor name works; no list to maintain.
CREATE FUNCTION ledger._author_ok(p text) RETURNS boolean LANGUAGE sql IMMUTABLE AS
$$ SELECT coalesce(p = 'user:owner' OR (p ~ '^[a-z][a-z0-9-]{0,31}:[A-Za-z0-9._-]{1,128}$' AND p !~ '^user:'), false) $$;
-- Session key for "same session" comparisons; ids imported from older ledgers may carry an 'agent:' prefix.
CREATE FUNCTION ledger._sid(p text) RETURNS text LANGUAGE sql IMMUTABLE AS $$ SELECT regexp_replace(p, '^agent:', '') $$;

CREATE TABLE ledger.project (
  id text PRIMARY KEY,
  code text NOT NULL UNIQUE CHECK (code ~ '^[A-Z][A-Z0-9_]{0,31}$'),
  title text NOT NULL
);
CREATE TABLE ledger.category (code text PRIMARY KEY, name text NOT NULL, definition text NOT NULL);

CREATE TABLE ledger.task (
  id text PRIMARY KEY,
  project_id text NOT NULL REFERENCES ledger.project,
  title text NOT NULL CHECK (length(title) BETWEEN 1 AND 300),
  purpose text NOT NULL CHECK (length(purpose) <= 4000),
  success_condition text NOT NULL CHECK (length(success_condition) <= 4000),
  state text NOT NULL CHECK (state IN ('UNKNOWN','PLANNED','ACTIVE','BLOCKED','REPORTED_DONE','VERIFIED','CANCELLED')),
  owner_actor_id text,
  origin_ref text,
  origin_quote text CHECK (length(origin_quote) <= 2000),
  version int NOT NULL DEFAULT 1,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL
);

-- Defect ids are '<project code>-D-<number>' (save() checks the prefix against the row's project).
CREATE TABLE ledger.defect (
  id text PRIMARY KEY CHECK (id ~ '^[A-Z][A-Z0-9_]{0,31}-D-[0-9]{4,}$'),
  project_id text NOT NULL REFERENCES ledger.project,
  title text NOT NULL CHECK (length(title) BETWEEN 1 AND 300),
  category text NOT NULL REFERENCES ledger.category,
  severity text NOT NULL CHECK (severity IN ('P1','P2','P3','UNRATED')),
  status text NOT NULL CHECK (status IN ('OPEN','SOURCE_FIXED','VERIFIED','NOT_DEFECT','DISPUTED','MERGED')),
  merged_into text REFERENCES ledger.defect DEFERRABLE,
  behavior text NOT NULL CHECK (length(behavior) <= 4000),
  root_cause text NOT NULL CHECK (length(root_cause) <= 4000),
  owner_actor_id text,
  version int NOT NULL DEFAULT 1,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CHECK ((status = 'MERGED') = (merged_into IS NOT NULL)),
  CHECK (merged_into IS DISTINCT FROM id)
);
CREATE INDEX defect_project_idx ON ledger.defect (project_id, status);

CREATE TABLE ledger.defect_fix (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  defect_id text NOT NULL REFERENCES ledger.defect,
  repo text NOT NULL,
  commit_sha text NOT NULL CHECK (commit_sha ~ '^[0-9a-f]{40}$'),
  branch text,
  ci_ref text CHECK (ci_ref IS NULL OR length(btrim(ci_ref)) > 0),
  ci_conclusion text CHECK (ci_conclusion IN ('success','failure','cancelled')),
  ci_completed_at timestamptz,
  recorded_by text NOT NULL,
  recorded_at timestamptz NOT NULL,
  imported boolean NOT NULL DEFAULT false,      -- true: copied from an older ledger, recorded_by is a legacy id
  UNIQUE (defect_id, repo, commit_sha),
  CHECK (ci_conclusion IS NULL OR ci_ref IS NOT NULL),
  CHECK (imported OR ledger._author_ok(recorded_by))
);

CREATE TABLE ledger.defect_task (
  defect_id text NOT NULL REFERENCES ledger.defect,
  task_id text NOT NULL REFERENCES ledger.task,
  PRIMARY KEY (defect_id, task_id)
);
CREATE TABLE ledger.defect_relation (
  a text NOT NULL REFERENCES ledger.defect,
  b text NOT NULL REFERENCES ledger.defect,
  kind text NOT NULL CHECK (kind IN ('same_site','caused_by','blocks','same_family','same_fix_unit')),
  PRIMARY KEY (a, b, kind),
  CHECK (a < b)
);

CREATE TABLE ledger.condition (
  id text PRIMARY KEY,
  project_id text NOT NULL REFERENCES ledger.project,
  release text NOT NULL,
  goal_label text,
  code text NOT NULL,
  condition_text text NOT NULL CHECK (length(condition_text) <= 4000),
  state text NOT NULL DEFAULT 'UNKNOWN' CHECK (state IN ('UNKNOWN','MET','NOT_MET')),
  version int NOT NULL DEFAULT 1,
  UNIQUE (project_id, release, code)
);
CREATE TABLE ledger.condition_link (
  condition_id text NOT NULL REFERENCES ledger.condition,
  task_id text REFERENCES ledger.task,
  defect_id text REFERENCES ledger.defect,
  CHECK (num_nonnulls(task_id, defect_id) = 1),
  UNIQUE NULLS NOT DISTINCT (condition_id, task_id, defect_id)
);

-- Which session may write which project. Written only by ledger_owner (grant_session / revoke_session); rows are never deleted.
CREATE TABLE ledger.session_grant (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  author_id text NOT NULL CHECK (ledger._author_ok(author_id)),
  project text NOT NULL REFERENCES ledger.project,
  scope text NOT NULL CHECK (scope IN ('defect','task','fix','verify')),
  granted_by text NOT NULL DEFAULT 'user:owner' CHECK (granted_by = 'user:owner'),
  granted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  expires_at timestamptz,
  revoked_at timestamptz,
  note text CHECK (length(note) <= 500),
  CHECK (expires_at IS NULL OR expires_at > granted_at),
  CHECK (revoked_at IS NULL OR revoked_at >= granted_at)
);
CREATE UNIQUE INDEX session_grant_one_open ON ledger.session_grant (author_id, project, scope) WHERE revoked_at IS NULL;

CREATE TABLE ledger.history (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  at timestamptz NOT NULL DEFAULT clock_timestamp(),
  author_id text NOT NULL,                       -- declared '<vendor>:<session-id>'; imported rows keep the old actor id
  login name NOT NULL DEFAULT session_user,      -- the role login that made the write
  actor_assurance text NOT NULL CHECK (actor_assurance IN ('DECLARED','LEGACY_LOGIN','LEGACY_SHARED','LEGACY_UNKNOWN')),
  entity text NOT NULL CHECK (entity IN ('defect','task','condition','defect_fix','defect_task','defect_relation','condition_link')),
  entity_id text NOT NULL,
  op text NOT NULL CHECK (op IN ('CREATE','EDIT','TRANSITION','LINK','UNLINK','CORRECT','IMPORT','LEGACY_NOTE','LEGACY_CHANGE')),
  from_state text,
  to_state text,
  changes jsonb NOT NULL DEFAULT '{}',
  reason text NOT NULL,
  evidence_uri text,
  evidence_sha256 text CHECK (evidence_sha256 ~ '^[0-9a-f]{64}$'),
  corrects_id bigint REFERENCES ledger.history,
  request_id uuid NOT NULL,
  legacy_ref text,
  CHECK ((op = 'TRANSITION') = (to_state IS NOT NULL)),
  CHECK ((op = 'CORRECT') = (corrects_id IS NOT NULL)),
  CHECK (legacy_ref IS NOT NULL OR length(btrim(reason)) BETWEEN 10 AND 1000),
  CHECK (op NOT IN ('IMPORT','LEGACY_NOTE','LEGACY_CHANGE') OR legacy_ref IS NOT NULL),
  CHECK (legacy_ref IS NOT NULL OR (actor_assurance = 'DECLARED' AND ledger._author_ok(author_id))),
  CHECK (legacy_ref IS NOT NULL OR op <> 'TRANSITION' OR to_state NOT IN ('VERIFIED','MET','NOT_MET') OR login = 'ledger_reviewer')
);
CREATE INDEX history_entity_idx ON ledger.history (entity, entity_id, id);
CREATE INDEX history_author_idx ON ledger.history (author_id, at);

CREATE TABLE ledger.request_receipt (
  request_id uuid PRIMARY KEY,
  operation text NOT NULL,
  payload_sha256 text NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
  author_id text NOT NULL CHECK (ledger._author_ok(author_id)),
  login name NOT NULL DEFAULT session_user,
  response jsonb,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

-- Schema version bookkeeping.
CREATE TABLE ledger._migration (step text PRIMARY KEY, done_at timestamptz NOT NULL DEFAULT clock_timestamp(), detail jsonb NOT NULL DEFAULT '{}');

-- History is append-only, for every role including the owner.
CREATE FUNCTION ledger._immutable() RETURNS trigger LANGUAGE plpgsql AS
$$BEGIN RAISE EXCEPTION 'ledger.history is append-only (% refused)', TG_OP USING ERRCODE = '42501'; END$$;
CREATE TRIGGER history_no_update BEFORE UPDATE OR DELETE ON ledger.history FOR EACH ROW EXECUTE FUNCTION ledger._immutable();
CREATE TRIGGER history_no_truncate BEFORE TRUNCATE ON ledger.history FOR EACH STATEMENT EXECUTE FUNCTION ledger._immutable();

RESET ROLE;
INSERT INTO ledger._migration (step, detail) VALUES ('schema', jsonb_build_object('by', session_user)),
                                                   ('schema_v2', jsonb_build_object('by', session_user, 'how', 'fresh install'));
COMMIT;
