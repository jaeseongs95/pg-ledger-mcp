-- Grants of the account of the admin web and of the owner commands of ledger-cli (operator step, applied by
-- `ledger-cli migrate --admin-account=<account>` through the owner account, after the schema is applied and again after every
-- upgrade). ${account} is replaced by the account name. ANSI GRANT only. The account has no DDL right, no write on the ledger
-- content (defects, tasks, fixes, cases, links, conditions, history) and no access to the receipts; it has no UPDATE or
-- DELETE on history, schema_history and the owner's audit trail.
-- Each right below is the least that a path of the owner runs: project-add, code-set, grant, revoke, key-register,
-- key-revoke, driver-register and the pages of the console.
-- (Letting the account see the schema, for example USAGE on it, is a step of the DBMS and not part of this file.)

-- the console shows the ledger content read only
GRANT SELECT ON task TO ${account};
GRANT SELECT ON defect TO ${account};
GRANT SELECT ON defect_fix TO ${account};
GRANT SELECT ON defect_case TO ${account};
GRANT SELECT ON defect_task TO ${account};
GRANT SELECT ON defect_relation TO ${account};
GRANT SELECT ON release_condition TO ${account};
GRANT SELECT ON condition_link TO ${account};
GRANT SELECT ON history TO ${account};
GRANT SELECT ON schema_history TO ${account};
-- project-add: insert (the counters are advanced by the MCP server)
GRANT SELECT, INSERT ON project TO ${account};
-- code-set: insert, update and the revision bump
GRANT SELECT, INSERT, UPDATE ON common_code TO ${account};
GRANT SELECT, UPDATE ON code_revision TO ${account};
-- UPDATE: the rows of the surrogate key counters (IdAllocator.next)
GRANT SELECT, UPDATE ON id_counter TO ${account};
-- grant and revoke; key-register and key-revoke (a key is closed, never deleted)
GRANT SELECT, INSERT, UPDATE ON session_grant TO ${account};
GRANT SELECT, INSERT, UPDATE ON session_key TO ${account};
-- the owner's audit trail is append only
GRANT SELECT, INSERT ON owner_audit TO ${account};
