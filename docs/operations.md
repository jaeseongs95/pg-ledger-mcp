# Operations and incident response

[한국어](operations.ko.md) · [Documentation index](guide.md)

## Install and check the instance

Follow the [installation and configuration reference](installation.md) for the host prerequisites, service accounts, ports, and settings. The [administrator guide](administration.md) covers owner sign-in and permission management.

On Linux, these read-only checks use the default instance name and ports. Substitute the names and ports configured for your installation.

```sh
systemctl is-active veridger.service veridger-admin.service
systemctl is-active veridger-updater.path
curl -fsS --max-time 5 http://127.0.0.1:8765/healthz
curl -fsS --max-time 5 http://127.0.0.1:8865/healthz
```

On Windows, check the two WinSW services with `Get-Service veridger,veridger-admin` and query the same health endpoints. Run `ledger-cli mcp-check` with the instance's settings to confirm the MCP tools are reachable. Sign in to the admin console and open a project to check the owner path too.

## Update and migrate

- For an update within a major version, open **Updates** and stage the verified release. Follow the [automatic PostgreSQL schema update procedure](installation.md#automatic-postgresql-schema-updates) to provision the updater's host configuration and migration credentials.
- When the target release changes the schema, the host updater stops both services, creates a PostgreSQL dump, applies pending schema versions and application grants, then checks both services. Confirm the updater result, installed version, and both health checks.
- Follow the target release notes for major-version upgrades. Use the [1.x migration guide](migration.md) for a ledger imported from 1.x.

## Back up and restore

Keep a consistent backup of the database, attachment files, attachment keys, and the settings and secrets needed to start the instance. Record the installed release and schema version with the backup. Use a quiesced instance or a backup method that gives a consistent set. Protect the backup with the same access restrictions as the instance.

Restore to an isolated instance with matching binaries and settings. Confirm both services start and can read the restored records. Run `ledger-cli attachments-check` and confirm there are zero missing or damaged attachments. A dump created for a schema update covers the database; include the attachment files and keys in your backup plan. See [attachment storage and checks](mcp.md#attachments).

## Diagnose a failure

| Symptom | What to check |
|---|---|
| Public endpoint returns `502` or `503` | Compare the direct host health endpoints with the public route. If the host is healthy, inspect the reverse proxy or tunnel. If the host fails, inspect the service logs, PostgreSQL availability, and disk space. |
| Update reports `RECOVERY_REQUIRED` | Read the backup path and target version in `.schema-update-in-progress`. Inspect `backup.log` and `migration.log` in the backup directory. Complete the migration or restore the matching database and JARs. Clear the marker only after both services and the schema agree. |
| Service reports `SCHEMA_PENDING` | Check the installed binaries and migration history. Run the documented migration for that release with the schema owner and application grants. Preserve the recorded migration files and checksums. |
| Reads work but a write returns `HOST_ATTESTATION_REQUIRED` | Check the host hook and the session identity using the [agent integration checklist](client-hooks.md#verify-the-connection). |
| Write returns `SESSION_NOT_GRANTED` | Check the actual host and session ID, target project, permission, and expiry in **Session grants**. |

For Linux logs and storage, start with `journalctl -u veridger.service -u veridger-admin.service -u veridger-updater.service -n 100` and `df -h /run /opt/veridger`. On Windows, consult the WinSW logs in the instance's `logs` directory. Keep credentials and session IDs out of shared incident notes.
