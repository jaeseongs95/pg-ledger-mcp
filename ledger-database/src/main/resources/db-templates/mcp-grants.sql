-- Grants of the account of the MCP server (operator step, applied by `ledger-cli migrate --mcp-account=<account>` through the
-- owner account, after the schema is applied and again after every upgrade). ${account} is replaced by the account name.
-- ANSI GRANT only. The account has no DDL right, no right on the owner's audit trail, no write on project administration,
-- common codes, grant administration and session keys, and no UPDATE or DELETE on history and schema_history.
-- Each right below is the least that a path of the server runs: the write operations (save, transition, record_fix,
-- record_case, link, correct, batch, review), the write gate, whoami, the receipts and the reads.
-- (Letting the account see the schema, for example USAGE on it, is a step of the DBMS and not part of this file.)

-- UPDATE: the counters next_defect_no and next_task_no (IdAllocator); no INSERT and no DELETE: projects are the owner's
GRANT SELECT, UPDATE ON project TO ${account};
-- read only: the codes are the owner's (admin web, ledger-cli)
GRANT SELECT ON common_code TO ${account};
GRANT SELECT ON code_revision TO ${account};
-- UPDATE: the rows of the surrogate key counters (IdAllocator.next)
GRANT SELECT, UPDATE ON id_counter TO ${account};
-- save, transition and review: insert, update and the row locks (UPDATE ... SET version = version)
GRANT SELECT, INSERT, UPDATE ON task TO ${account};
GRANT SELECT, INSERT, UPDATE ON defect TO ${account};
GRANT SELECT, INSERT, UPDATE ON release_condition TO ${account};
GRANT SELECT, INSERT, UPDATE ON defect_fix TO ${account};
-- record_case: insert; the case lock is an UPDATE of defect
GRANT SELECT, INSERT ON defect_case TO ${account};
-- link: the only deletes
GRANT SELECT, INSERT, DELETE ON defect_task TO ${account};
GRANT SELECT, INSERT, DELETE ON defect_relation TO ${account};
GRANT SELECT, INSERT, DELETE ON condition_link TO ${account};
-- the write gate stamps a live grant with a conditional UPDATE (GrantStateRepository.markUsed); a grant is issued and
-- revoked by the owner only, so no INSERT and no DELETE
GRANT SELECT, UPDATE ON session_grant TO ${account};
-- the public keys are read to verify an attestation; no write: a key is registered and closed by the owner only
-- (OPEN: the write gate keeps a live key locked with UPDATE session_key SET note = note, see TEST_CASES INS-002)
GRANT SELECT ON session_key TO ${account};
-- history is append only
GRANT SELECT, INSERT ON history TO ${account};
-- a receipt is inserted and completed once
GRANT SELECT, INSERT, UPDATE ON request_receipt TO ${account};
GRANT SELECT ON schema_history TO ${account};
