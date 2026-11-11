-- V2: the append-only history and the request receipts. Nothing updates or deletes a history row: the
-- application only inserts, and the operator's GRANT gives the application account no UPDATE or DELETE on it.

CREATE TABLE history (
  id BIGINT NOT NULL,
  at TIMESTAMP NOT NULL,
  author_id VARCHAR(200) NOT NULL,
  login VARCHAR(63) NOT NULL,
  actor_assurance VARCHAR(20) NOT NULL,
  entity VARCHAR(20) NOT NULL,
  entity_id VARCHAR(300) NOT NULL,
  op VARCHAR(20) NOT NULL,
  from_state VARCHAR(20),
  to_state VARCHAR(20),
  changes ${longtext} NOT NULL,
  before_data ${longtext},
  reason ${longtext} NOT NULL,
  evidence_uri VARCHAR(2000),
  evidence_sha256 VARCHAR(64),
  corrects_id BIGINT,
  request_id VARCHAR(36) NOT NULL,
  legacy_ref VARCHAR(200),
  CONSTRAINT pk_history PRIMARY KEY (id),
  CONSTRAINT fk_history_corrects FOREIGN KEY (corrects_id) REFERENCES history (id),
  CONSTRAINT ck_history_assurance CHECK (actor_assurance IN ('DECLARED', 'LEGACY_LOGIN', 'LEGACY_SHARED',
    'LEGACY_UNKNOWN')),
  CONSTRAINT ck_history_entity CHECK (entity IN ('DEFECT', 'TASK', 'CONDITION', 'DEFECT_FIX', 'DEFECT_TASK',
    'DEFECT_RELATION', 'CONDITION_LINK', 'DEFECT_CASE')),
  CONSTRAINT ck_history_op CHECK (op IN ('CREATE', 'EDIT', 'TRANSITION', 'LINK', 'UNLINK', 'CORRECT', 'IMPORT',
    'LEGACY_NOTE', 'LEGACY_CHANGE')),
  CONSTRAINT ck_history_transition CHECK ((op = 'TRANSITION' AND to_state IS NOT NULL)
    OR (op <> 'TRANSITION' AND to_state IS NULL)),
  CONSTRAINT ck_history_correct CHECK ((op = 'CORRECT' AND corrects_id IS NOT NULL)
    OR (op <> 'CORRECT' AND corrects_id IS NULL)),
  CONSTRAINT ck_history_legacy_op CHECK (op NOT IN ('IMPORT', 'LEGACY_NOTE', 'LEGACY_CHANGE')
    OR legacy_ref IS NOT NULL),
  CONSTRAINT ck_history_declared CHECK (legacy_ref IS NOT NULL OR actor_assurance = 'DECLARED'),
  CONSTRAINT ck_history_verify CHECK (legacy_ref IS NOT NULL OR op <> 'TRANSITION'
    OR to_state NOT IN ('VERIFIED', 'MET', 'NOT_MET') OR login = 'ledger_reviewer')
);
CREATE INDEX ix_history_entity ON history (entity, entity_id, id);
CREATE INDEX ix_history_author ON history (author_id, at);
CREATE INDEX ix_history_request ON history (request_id);

CREATE TABLE request_receipt (
  request_id VARCHAR(36) NOT NULL,
  operation VARCHAR(20) NOT NULL,
  payload_sha256 VARCHAR(64) NOT NULL,
  author_id VARCHAR(200) NOT NULL,
  login VARCHAR(63) NOT NULL,
  response ${longtext},
  origin VARCHAR(10) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT pk_request_receipt PRIMARY KEY (request_id),
  CONSTRAINT ck_receipt_operation CHECK (operation IN ('SAVE', 'TRANSITION', 'RECORD_FIX', 'RECORD_CASE', 'LINK',
    'CORRECT', 'BATCH')),
  CONSTRAINT ck_receipt_origin CHECK (origin IN ('NATIVE', 'LEGACY'))
);
