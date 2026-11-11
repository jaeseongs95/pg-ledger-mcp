-- V1: the core tables. ANSI SQL only: no trigger, procedure, view, sequence or identity column, no boolean
-- column, no DEFAULT on a time column. Every id and counter is assigned by the application. Times are UTC
-- TIMESTAMP values written by the application clock. ${longtext} is the only per-DBMS part (see SchemaMigrator).

CREATE TABLE project (
  id VARCHAR(64) NOT NULL,
  code VARCHAR(32) NOT NULL,
  title VARCHAR(200) NOT NULL,
  next_defect_no BIGINT DEFAULT 0 NOT NULL,
  next_task_no BIGINT DEFAULT 0 NOT NULL,
  version BIGINT NOT NULL,
  created_by VARCHAR(200) NOT NULL,
  CONSTRAINT pk_project PRIMARY KEY (id),
  CONSTRAINT uq_project_code UNIQUE (code)
);

CREATE TABLE common_code (
  code_group VARCHAR(20) NOT NULL,
  code VARCHAR(32) NOT NULL,
  name_ko VARCHAR(200) NOT NULL,
  name_en VARCHAR(200) NOT NULL,
  description_ko VARCHAR(500),
  description_en VARCHAR(500),
  sort_order INTEGER NOT NULL,
  state VARCHAR(10) NOT NULL,
  created_by VARCHAR(200) NOT NULL,
  CONSTRAINT pk_common_code PRIMARY KEY (code_group, code),
  CONSTRAINT ck_common_code_group CHECK (code_group IN ('DEFECT_CATEGORY', 'DEFECT_SEVERITY', 'RELATION_KIND')),
  CONSTRAINT ck_common_code_state CHECK (state IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE code_revision (
  id INTEGER NOT NULL,
  revision BIGINT NOT NULL,
  CONSTRAINT pk_code_revision PRIMARY KEY (id),
  CONSTRAINT ck_code_revision_id CHECK (id = 1)
);

CREATE TABLE id_counter (
  name VARCHAR(30) NOT NULL,
  issued_no BIGINT NOT NULL,
  CONSTRAINT pk_id_counter PRIMARY KEY (name),
  CONSTRAINT ck_id_counter_name CHECK (name IN ('HISTORY', 'DEFECT_FIX', 'DEFECT_CASE', 'SESSION_GRANT', 'SESSION_KEY',
    'OWNER_AUDIT'))
);

CREATE TABLE task (
  id VARCHAR(120) NOT NULL,
  project_id VARCHAR(64) NOT NULL,
  title VARCHAR(300) NOT NULL,
  purpose ${longtext} NOT NULL,
  success_condition ${longtext} NOT NULL,
  state VARCHAR(20) NOT NULL,
  owner_actor_id VARCHAR(200),
  origin_ref VARCHAR(500),
  origin_quote VARCHAR(2000),
  version BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  created_by VARCHAR(200) NOT NULL,
  CONSTRAINT pk_task PRIMARY KEY (id),
  CONSTRAINT fk_task_project FOREIGN KEY (project_id) REFERENCES project (id),
  CONSTRAINT ck_task_state CHECK (state IN ('UNKNOWN', 'PLANNED', 'ACTIVE', 'BLOCKED', 'REPORTED_DONE', 'VERIFIED',
    'CANCELLED'))
);
CREATE INDEX ix_task_project ON task (project_id, created_at, id);

CREATE TABLE defect (
  id VARCHAR(120) NOT NULL,
  project_id VARCHAR(64) NOT NULL,
  title VARCHAR(300) NOT NULL,
  category VARCHAR(32) NOT NULL,
  severity VARCHAR(32) NOT NULL,
  status VARCHAR(20) NOT NULL,
  merged_into VARCHAR(120),
  behavior ${longtext} NOT NULL,
  root_cause ${longtext} NOT NULL,
  owner_actor_id VARCHAR(200),
  case_seq BIGINT DEFAULT 0 NOT NULL,
  version BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  created_by VARCHAR(200) NOT NULL,
  CONSTRAINT pk_defect PRIMARY KEY (id),
  CONSTRAINT fk_defect_project FOREIGN KEY (project_id) REFERENCES project (id),
  CONSTRAINT fk_defect_merged_into FOREIGN KEY (merged_into) REFERENCES defect (id),
  CONSTRAINT ck_defect_status CHECK (status IN ('OPEN', 'SOURCE_FIXED', 'VERIFIED', 'NOT_DEFECT', 'DISPUTED',
    'MERGED')),
  CONSTRAINT ck_defect_merged CHECK ((status = 'MERGED' AND merged_into IS NOT NULL)
    OR (status <> 'MERGED' AND merged_into IS NULL)),
  CONSTRAINT ck_defect_merged_self CHECK (merged_into IS NULL OR merged_into <> id)
);
CREATE INDEX ix_defect_project ON defect (project_id, created_at, id);
CREATE INDEX ix_defect_status ON defect (project_id, status);
CREATE INDEX ix_defect_merged_into ON defect (merged_into);

CREATE TABLE defect_fix (
  id BIGINT NOT NULL,
  defect_id VARCHAR(120) NOT NULL,
  repo VARCHAR(300) NOT NULL,
  commit_sha VARCHAR(40) NOT NULL,
  branch VARCHAR(300),
  ci_ref VARCHAR(500),
  ci_conclusion VARCHAR(10),
  ci_completed_at TIMESTAMP,
  recorded_by VARCHAR(200) NOT NULL,
  recorded_at TIMESTAMP NOT NULL,
  origin VARCHAR(10) NOT NULL,
  CONSTRAINT pk_defect_fix PRIMARY KEY (id),
  CONSTRAINT fk_defect_fix_defect FOREIGN KEY (defect_id) REFERENCES defect (id),
  CONSTRAINT uq_defect_fix_commit UNIQUE (defect_id, repo, commit_sha),
  CONSTRAINT ck_defect_fix_conclusion CHECK (ci_conclusion IS NULL OR ci_conclusion IN ('SUCCESS', 'FAILURE',
    'CANCELLED')),
  CONSTRAINT ck_defect_fix_ci CHECK (ci_conclusion IS NULL OR ci_ref IS NOT NULL),
  CONSTRAINT ck_defect_fix_origin CHECK (origin IN ('NATIVE', 'IMPORTED'))
);

CREATE TABLE defect_case (
  id BIGINT NOT NULL,
  defect_id VARCHAR(120) NOT NULL,
  observation ${longtext} NOT NULL,
  evidence VARCHAR(2000),
  user_quote VARCHAR(2000),
  observed_at TIMESTAMP,
  recorded_by VARCHAR(200) NOT NULL,
  recorded_at TIMESTAMP NOT NULL,
  CONSTRAINT pk_defect_case PRIMARY KEY (id),
  CONSTRAINT fk_defect_case_defect FOREIGN KEY (defect_id) REFERENCES defect (id)
);
CREATE INDEX ix_defect_case_defect ON defect_case (defect_id, id);

CREATE TABLE defect_task (
  defect_id VARCHAR(120) NOT NULL,
  task_id VARCHAR(120) NOT NULL,
  created_by VARCHAR(200) NOT NULL,
  CONSTRAINT pk_defect_task PRIMARY KEY (defect_id, task_id),
  CONSTRAINT fk_defect_task_defect FOREIGN KEY (defect_id) REFERENCES defect (id),
  CONSTRAINT fk_defect_task_task FOREIGN KEY (task_id) REFERENCES task (id)
);

CREATE TABLE defect_relation (
  a VARCHAR(120) NOT NULL,
  b VARCHAR(120) NOT NULL,
  kind VARCHAR(32) NOT NULL,
  created_by VARCHAR(200) NOT NULL,
  CONSTRAINT pk_defect_relation PRIMARY KEY (a, b, kind),
  CONSTRAINT fk_defect_relation_a FOREIGN KEY (a) REFERENCES defect (id),
  CONSTRAINT fk_defect_relation_b FOREIGN KEY (b) REFERENCES defect (id),
  CONSTRAINT ck_defect_relation_order CHECK (a < b)
);

CREATE TABLE release_condition (
  id VARCHAR(120) NOT NULL,
  project_id VARCHAR(64) NOT NULL,
  release_name VARCHAR(100) NOT NULL,
  goal_label VARCHAR(200),
  code VARCHAR(64) NOT NULL,
  condition_text ${longtext} NOT NULL,
  state VARCHAR(10) NOT NULL,
  version BIGINT NOT NULL,
  created_by VARCHAR(200) NOT NULL,
  CONSTRAINT pk_release_condition PRIMARY KEY (id),
  CONSTRAINT fk_condition_project FOREIGN KEY (project_id) REFERENCES project (id),
  CONSTRAINT uq_condition_code UNIQUE (project_id, release_name, code),
  CONSTRAINT ck_condition_state CHECK (state IN ('UNKNOWN', 'MET', 'NOT_MET'))
);

CREATE TABLE condition_link (
  link_key VARCHAR(400) NOT NULL,
  condition_id VARCHAR(120) NOT NULL,
  task_id VARCHAR(120),
  defect_id VARCHAR(120),
  created_by VARCHAR(200) NOT NULL,
  CONSTRAINT pk_condition_link PRIMARY KEY (link_key),
  CONSTRAINT fk_condition_link_cond FOREIGN KEY (condition_id) REFERENCES release_condition (id),
  CONSTRAINT fk_condition_link_task FOREIGN KEY (task_id) REFERENCES task (id),
  CONSTRAINT fk_condition_link_defect FOREIGN KEY (defect_id) REFERENCES defect (id),
  CONSTRAINT ck_condition_link_target CHECK ((task_id IS NOT NULL AND defect_id IS NULL)
    OR (task_id IS NULL AND defect_id IS NOT NULL))
);

CREATE TABLE session_grant (
  id BIGINT NOT NULL,
  author_id VARCHAR(200) NOT NULL,
  project_id VARCHAR(64) NOT NULL,
  scope VARCHAR(10) NOT NULL,
  granted_by VARCHAR(200) NOT NULL,
  granted_at TIMESTAMP NOT NULL,
  expires_at TIMESTAMP,
  revoked_at TIMESTAMP,
  last_used_at TIMESTAMP,
  note VARCHAR(500),
  active_key VARCHAR(300) NOT NULL,
  CONSTRAINT pk_session_grant PRIMARY KEY (id),
  CONSTRAINT fk_session_grant_project FOREIGN KEY (project_id) REFERENCES project (id),
  CONSTRAINT uq_session_grant_active UNIQUE (active_key),
  CONSTRAINT ck_session_grant_scope CHECK (scope IN ('DEFECT', 'TASK', 'FIX', 'VERIFY')),
  CONSTRAINT ck_session_grant_expiry CHECK (expires_at IS NULL OR expires_at > granted_at),
  CONSTRAINT ck_session_grant_revoked CHECK (revoked_at IS NULL OR revoked_at >= granted_at)
);
CREATE INDEX ix_session_grant_author ON session_grant (author_id, project_id, scope);

-- The public key an author signs its writes with (Ed25519, X.509 DER in base64). Registered by the owner only
-- (admin web, CLI), never through a tool. A key is replaced or revoked by closing its row: revoked_at is set and both
-- key columns become revoked:<id>; rows are never deleted. active_key (author|live) keeps one live key per author and
-- fingerprint_key (the fingerprint while live) keeps one author per live key.
CREATE TABLE session_key (
  id BIGINT NOT NULL,
  author_id VARCHAR(200) NOT NULL,
  public_key VARCHAR(128) NOT NULL,
  fingerprint VARCHAR(64) NOT NULL,
  registered_by VARCHAR(200) NOT NULL,
  registered_at TIMESTAMP NOT NULL,
  revoked_at TIMESTAMP,
  note VARCHAR(500),
  active_key VARCHAR(300) NOT NULL,
  fingerprint_key VARCHAR(100) NOT NULL,
  CONSTRAINT pk_session_key PRIMARY KEY (id),
  CONSTRAINT uq_session_key_active UNIQUE (active_key),
  CONSTRAINT uq_session_key_fingerprint UNIQUE (fingerprint_key),
  CONSTRAINT ck_session_key_revoked CHECK (revoked_at IS NULL OR revoked_at >= registered_at)
);
CREATE INDEX ix_session_key_author ON session_key (author_id, id);

-- The owner's audit trail: who (the identity the owner signed in with at the admin web, or the command line tool) did
-- which owner operation (grant, revoke, key registration, replacement or revocation, code change, project creation, driver
-- registration) and when. Append-only:
-- the application only inserts, and the operator's GRANT gives the application account no UPDATE or DELETE on it.
CREATE TABLE owner_audit (
  id BIGINT NOT NULL,
  at TIMESTAMP NOT NULL,
  actor VARCHAR(300) NOT NULL,
  operation VARCHAR(20) NOT NULL,
  subject VARCHAR(300) NOT NULL,
  detail VARCHAR(1000),
  CONSTRAINT pk_owner_audit PRIMARY KEY (id),
  CONSTRAINT ck_owner_audit_operation CHECK (operation IN ('GRANT_ISSUED', 'GRANT_REVOKED', 'KEY_REGISTERED',
    'KEY_REPLACED', 'KEY_REVOKED', 'CODE_SET', 'PROJECT_ADDED', 'DRIVER_REGISTERED', 'IMPORT'))
);
CREATE INDEX ix_owner_audit_subject ON owner_audit (subject, id);
