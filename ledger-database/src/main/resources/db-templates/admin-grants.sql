-- Grants of the account of the admin web and of the owner commands of ledger-cli (operator step, applied by
-- `ledger-cli migrate --admin-account=<account>` through the owner account). ${account} is replaced by the account name.
-- ANSI GRANT only.
-- (Letting the account see the schema, for example USAGE on it, is a step of the DBMS and not part of this file.)

GRANT SELECT, INSERT, UPDATE ON project TO ${account};
GRANT SELECT, INSERT, UPDATE ON common_code TO ${account};
GRANT SELECT, UPDATE ON code_revision TO ${account};
GRANT SELECT, UPDATE ON id_counter TO ${account};
GRANT SELECT, INSERT, UPDATE ON task TO ${account};
GRANT SELECT, INSERT, UPDATE ON defect TO ${account};
GRANT SELECT, INSERT, UPDATE ON defect_fix TO ${account};
GRANT SELECT, INSERT ON defect_case TO ${account};
GRANT SELECT, INSERT, DELETE ON defect_task TO ${account};
GRANT SELECT, INSERT, DELETE ON defect_relation TO ${account};
GRANT SELECT, INSERT, UPDATE ON release_condition TO ${account};
GRANT SELECT, INSERT, DELETE ON condition_link TO ${account};
GRANT SELECT, INSERT, UPDATE ON session_grant TO ${account};
GRANT SELECT, INSERT, UPDATE ON session_key TO ${account};
GRANT SELECT, INSERT ON history TO ${account};
GRANT SELECT, INSERT ON owner_audit TO ${account};
GRANT SELECT, INSERT, UPDATE ON request_receipt TO ${account};
GRANT SELECT ON schema_history TO ${account};
