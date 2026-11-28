# Veridger guide

[한국어](guide.ko.md) · [Project overview](../README.md)

A universal ledger for work done by AI agents across software, film production, writing, research and operations, served as one MCP
endpoint. It records issues, specifications, tasks, milestone criteria, deliverables and their independent verification, together with
who did each one and why. Agents read and write through standard MCP tools; they never get a database login of their own.

This is the **personal edition**: one person installs it and runs it on their own computer or on a server of their own, and trusts the
whole installation target. The two services (the MCP server and the admin web) listen on the loopback address `127.0.0.1` only. Making
them reachable from another machine, with a reverse proxy and a TLS certificate, is the operator's, with a tool of the operator's
choice: the installers write no proxy configuration and order no certificate.

Version 2.0.0 is a rewrite in Java 25 and Spring Boot with its own tool contract (every write tool takes a required `operationKey`,
every response uses the `ApiResult.v1` envelope, and no argument of an earlier version is accepted next to the new ones); what changed
for a client is stated in the release notes ([2.0.0](release-notes/2.0.0.md), [2.1.0](release-notes/2.1.0.md), [2.1.1](release-notes/2.1.1.md), [3.0.0](release-notes/3.0.0.md), [4.0.0](release-notes/4.0.0.md), [4.0.1](release-notes/4.0.1.md), [4.1.0](release-notes/4.1.0.md), [4.2.0](release-notes/4.2.0.md), [4.3.0](release-notes/4.3.0.md), [4.3.1](release-notes/4.3.1.md), [4.3.2](release-notes/4.3.2.md), [4.4.0](release-notes/4.4.0.md), [4.4.1](release-notes/4.4.1.md), [5.0.0](release-notes/5.0.0.md), [6.0.0](release-notes/6.0.0.md), [7.0.0](release-notes/7.0.0.md)); the format of release notes is in
[docs/RELEASE_NOTES_FORMAT.md](RELEASE_NOTES_FORMAT.md).

## Contents

| Document | Use it to |
|---|---|
| [README](../README.md) | Get an overview, see the admin console and quality badges, and get started. |
| [User guide](user-guide.md) | Navigate projects, versions, specifications, issues, tasks, release criteria, and history. |
| [Administrator guide](administration.md) | Manage owner authentication, session permissions, attachments, and update settings. |
| [Agent integration](client-hooks.md) | Connect an MCP client and install and verify the host hook. |
| [Practical workflows](workflows.md) | Coordinate multiple agents, review evidence independently, and make release decisions. |
| [Architecture](architecture.md) | Understand the modules, domain model, transactions, and trust boundaries. |
| [MCP reference](mcp.md) | Find tool inputs, outputs, errors, and permission requirements. |
| [Operations and incident response](operations.md) | Install, migrate, back up, recover, and diagnose service failures. |

[Ledger concepts](concepts.md) · [Installation and configuration](installation.md) · [Migration from 1.x](migration.md) · [Development and testing](development.md)

<details>
<summary>Links from the previous guide</summary>

- <a id="concepts"></a>[Concepts](concepts.md#concepts)
- <a id="mcp-tools"></a>[MCP tools](mcp.md#mcp-tools)
- <a id="attachments"></a>[Attachments](mcp.md#attachments)
- <a id="modules"></a>[Modules](architecture.md#modules)
- <a id="configuration"></a>[Configuration](installation.md#configuration)
- <a id="installation"></a>[Installation](installation.md#installation)
- <a id="linux-server"></a>[Linux server](installation.md#linux-server)
- <a id="reaching-the-servers-from-outside"></a>[Reaching the servers from outside](installation.md#reaching-the-servers-from-outside)
- <a id="windows-a-personal-pc-or-a-server"></a>[Windows: a personal PC or a server](installation.md#windows-a-personal-pc-or-a-server)
- <a id="automatic-postgresql-schema-updates"></a>[Automatic PostgreSQL schema updates](installation.md#automatic-postgresql-schema-updates)
- <a id="first-setup"></a>[First setup](installation.md#first-setup)
- <a id="after-the-installation"></a>[After the installation](installation.md#after-the-installation)
- <a id="ledger-cli"></a>[ledger-cli](administration.md#ledger-cli)
- <a id="admin-web"></a>[Admin web](administration.md#admin-web)
- <a id="client-hooks"></a>[Client hooks](client-hooks.md#client-hooks)
- <a id="upgrading-from-1x"></a>[Upgrading from 1.x](migration.md#upgrading-from-1x)
- <a id="what-the-import-does"></a>[What the import does](migration.md#what-the-import-does)
- <a id="settings-of-the-1x-source"></a>[Settings of the 1.x source](migration.md#settings-of-the-1x-source)
- <a id="build-and-test"></a>[Build and test](development.md#build-and-test)
- <a id="the-test-database"></a>[The test database](development.md#the-test-database)
- <a id="optional-properties-for-machines-with-few-resources"></a>[Optional properties for machines with few resources](development.md#optional-properties-for-machines-with-few-resources)
- <a id="build-speed"></a>[Build speed](development.md#build-speed)
- <a id="layout"></a>[Layout](development.md#layout)

</details>

## License

From version 2.0, veridger is licensed under the GNU Affero General Public License v3.0 or (at your option) any later version (`AGPL-3.0-or-later`); see [LICENSE](../LICENSE), [ADDITIONAL-TERMS.md](../ADDITIONAL-TERMS.md) and [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).

Copyright (C) 2026 Jaeseong Song

`AGPL-3.0-or-later` applies to 2.0.0 and to every release published after it, including any later maintenance release of the 1.x line. The MIT License continues to apply only to the copies already published under it, that is v1.0.0 and v1.1.0.

Under section 13 of the license, whoever runs a modified version as a network service must offer the users of that service the corresponding source code.
The admin web shows a link to the source code in the footer of its pages; its address is the setting `ledger.source.url`, by default the
public repository of the project. Whoever runs a modified version sets `ledger.source.url` to the place where the corresponding source of that
modified version is offered. The setting takes an `http` or `https` address; the answers of the MCP server do not carry it.
