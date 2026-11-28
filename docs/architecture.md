# Architecture

[한국어](architecture.ko.md) · [Guide index](guide.md)

Veridger runs the MCP interface and the owner console as separate services that share the application and database layers.
The MCP interface uses session permissions for agent writes. The console uses owner authentication for administration.
The application records changes alongside actor identity and evidence, and independent review governs the final outcomes.
PostgreSQL stores the ledger and its append-only history. Schema and grants are applied by the CLI.

## Modules

The build is a Gradle multi-module project.

| Module | What |
|---|---|
| `ledger-domain` | aggregates, value types, state rules, error codes and `ToolContract`, the list of tool names |
| `ledger-attestation` | `_hostAttestation` wire helpers and RFC 8785 canonical JSON utilities shared by the hook and the server (JDK only) |
| `ledger-files` | owner-only POSIX/ACL file and directory protection and `NOFOLLOW_LINKS` inspection, shared by the server, the CLI and the hook (JDK only) |
| `ledger-database` | Spring Data JDBC repositories, the schema files `V{n}__*.sql` and their runner, the loader of the registered JDBC driver |
| `ledger-application` | the application services: write gate, receipts, history, the write and read operations, and session grants |
| `ledger-mcp-server` | the Spring Boot MCP server (`ledger-mcp-server.jar`) |
| `ledger-admin-web` | the Spring Boot admin web for the owner (`ledger-admin-web.jar`) |
| `ledger-cli` | the owner command line tool (`ledger-cli.jar`) |
| `ledger-seed` | the seed bundle of a new installation, the default defect categories and the install defaults in one H2 file, and its reader; only `ledger-cli` uses it |
| `ledger-migrate-1x` | the separate tool that moves a 1.x installation to 2.0 (`ledger-migrate-1x.jar`, see Upgrading from 1.x); it uses `ledger-domain`, `ledger-database` and `ledger-application`, and the classes (the plain jar, none of its libraries) of `ledger-cli` for its command line machinery and secret store, so that the secret store is the one of the services; no other module uses it |
| `ledger-hook-client` | the light client of the hook (`ledger-client-hook.jar`, `java.base` only), the wire format, the state folder and the address file |
| `ledger-client-hook` | the resident program of the hook (`ledger-hook-resident.jar`, a Spring Boot application) |
| `ledger-system-test` | test code only: the system test of the packaged product, which starts the built jars against the local PostgreSQL |
| `ledger-arch-checks` | test code only: cross-module architecture, build-script, SQL/transaction boundary, and module dependency checks |

## Domain model

- Projects own versions and the records associated with them. A record can be linked to multiple versions of its project.
- Specifications define expected outcomes. Tasks track work. Issues capture observations and related evidence. Milestones group release criteria for Go/No-Go decisions.
- Deliverables and attachments support completion and review decisions. Session grants, operation receipts, and immutable history capture who was authorized to act, what was recorded, and why.

## Transactions and trust boundaries

State changes check the expected row version. The application commits a write, its history, and its operation receipt in one transaction. A batch commits its author operations together. A retry with the same operation key and payload returns the stored result if the session still holds the required grants.

Agent writes require host attestation and a live project grant. Independent review checks the reviewer's session against the parties who contributed to the record. The admin console uses owner authentication. PostgreSQL schema changes run as the schema owner, and application services connect through an application account. See the [administrator guide](administration.md) and [operating procedures](operations.md).
