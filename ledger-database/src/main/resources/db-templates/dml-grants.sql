-- Least-privilege grants for the application account, the one account of the MCP server, the admin web and the owner commands
-- of ledger-cli (operator step: `ledger-cli migrate --app-account=<account>` applies it through the DDL account, after the
-- schema and again after every upgrade). ${dml_account} is replaced by the account name. ANSI GRANT only. The account gets no DDL right, no UPDATE or
-- DELETE on history, attachment, attachment_file, attachment_storage and schema_history, and no DELETE except on link tables and upload_staging.

GRANT SELECT, INSERT, UPDATE ON project TO ${dml_account};
GRANT SELECT, INSERT, UPDATE ON project_version TO ${dml_account};
GRANT SELECT ON issue_project_version TO ${dml_account};
GRANT SELECT, INSERT, DELETE ON project_version_item TO ${dml_account};
GRANT SELECT, INSERT, UPDATE ON common_code TO ${dml_account};
GRANT SELECT, UPDATE ON code_revision TO ${dml_account};
GRANT SELECT, UPDATE ON id_counter TO ${dml_account};
GRANT SELECT, INSERT, UPDATE ON task TO ${dml_account};
GRANT SELECT, INSERT, UPDATE ON spec TO ${dml_account};
GRANT SELECT, INSERT, UPDATE ON issue TO ${dml_account};
GRANT SELECT, INSERT, UPDATE ON deliverable TO ${dml_account};
GRANT SELECT, INSERT ON issue_case TO ${dml_account};
GRANT SELECT, INSERT, DELETE ON issue_task TO ${dml_account};
GRANT SELECT, INSERT, DELETE ON issue_spec TO ${dml_account};
GRANT SELECT, INSERT, DELETE ON spec_task TO ${dml_account};
GRANT SELECT, INSERT, DELETE ON issue_relation TO ${dml_account};
GRANT SELECT, INSERT, UPDATE ON milestone_archive TO ${dml_account};
GRANT SELECT, INSERT, UPDATE ON milestone_criterion TO ${dml_account};
GRANT SELECT, INSERT, DELETE ON criterion_link TO ${dml_account};
GRANT SELECT, INSERT, UPDATE ON session_grant TO ${dml_account};
GRANT SELECT, INSERT, UPDATE ON session_key TO ${dml_account};
GRANT SELECT, INSERT, UPDATE ON session_token TO ${dml_account};
GRANT SELECT, INSERT ON history TO ${dml_account};
GRANT SELECT, INSERT ON owner_audit TO ${dml_account};
GRANT SELECT, INSERT ON driver_audit_key TO ${dml_account};
GRANT SELECT, INSERT ON owner_passkey_user TO ${dml_account};
GRANT SELECT, INSERT ON owner_passkey TO ${dml_account};
GRANT SELECT, INSERT ON owner_passkey_revocation TO ${dml_account};
GRANT SELECT, INSERT, UPDATE ON owner_passkey_code TO ${dml_account};
GRANT SELECT, INSERT, UPDATE ON owner_passkey_counter TO ${dml_account};
GRANT SELECT, INSERT ON attachment TO ${dml_account};
GRANT SELECT, INSERT ON attachment_file TO ${dml_account};
GRANT SELECT, INSERT ON attachment_storage TO ${dml_account};
GRANT SELECT, INSERT, UPDATE, DELETE ON upload_staging TO ${dml_account};
GRANT SELECT, UPDATE ON upload_quota_gate TO ${dml_account};
GRANT SELECT, INSERT, UPDATE ON request_receipt TO ${dml_account};
GRANT SELECT ON schema_history TO ${dml_account};
