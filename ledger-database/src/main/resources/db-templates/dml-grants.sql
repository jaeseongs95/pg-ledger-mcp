-- Least-privilege grants for the application account, the one account of the MCP server, the admin web and the owner commands
-- of ledger-cli (operator step: `ledger-cli migrate --app-account=<account>` applies it through the DDL account, after the
-- schema and again after every upgrade). ${dml_account} is replaced by the account name. ANSI GRANT only. The account gets no DDL right, no UPDATE or
-- DELETE on history and schema_history, and no DELETE except on the three link tables.
-- (Letting the account see the schema, for example USAGE on it, is a step of the DBMS and not part of this file.)

GRANT SELECT, INSERT, UPDATE ON project TO ${dml_account};
GRANT SELECT, INSERT, UPDATE ON common_code TO ${dml_account};
GRANT SELECT, UPDATE ON code_revision TO ${dml_account};
GRANT SELECT, UPDATE ON id_counter TO ${dml_account};
GRANT SELECT, INSERT, UPDATE ON task TO ${dml_account};
GRANT SELECT, INSERT, UPDATE ON defect TO ${dml_account};
GRANT SELECT, INSERT, UPDATE ON defect_fix TO ${dml_account};
GRANT SELECT, INSERT ON defect_case TO ${dml_account};
GRANT SELECT, INSERT, DELETE ON defect_task TO ${dml_account};
GRANT SELECT, INSERT, DELETE ON defect_relation TO ${dml_account};
GRANT SELECT, INSERT, UPDATE ON release_condition TO ${dml_account};
GRANT SELECT, INSERT, DELETE ON condition_link TO ${dml_account};
GRANT SELECT, INSERT, UPDATE ON session_grant TO ${dml_account};
GRANT SELECT, INSERT, UPDATE ON session_key TO ${dml_account};
GRANT SELECT, INSERT ON history TO ${dml_account};
GRANT SELECT, INSERT ON owner_audit TO ${dml_account};
GRANT SELECT, INSERT, UPDATE ON request_receipt TO ${dml_account};
GRANT SELECT ON schema_history TO ${dml_account};
