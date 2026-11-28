# MCP reference

[한국어](mcp.ko.md) · [Guide index](guide.md)

## MCP tools

The server speaks MCP over Streamable HTTP at `/mcp` (default `127.0.0.1:8765`) and answers `/healthz`. Callers present `ledger.mcp.token`. Every tool returns the `ApiResult.v1` envelope (`schemaVersion` `"1.0.0"`,
`ok`, `data`, `error`). Every tool input is closed and flat (no raw SQL query argument, no untyped map): an unknown argument is
refused, an object or an array argument must be sent as that JSON value (not as text), and the text `null` is a text, not a missing
argument. A JSON `null` on an optional field counts as not given; an optional text field is cleared with an empty string.

Write tools (authenticated author and a live grant are needed). Every write tool except `ledger_whoami` takes the required
`operationKey`:

| Tool | Channel | What |
|---|---|---|
| `ledger_whoami` | author | verify caller identity and list live project grants (read only) |
| `ledger_issue_save` | author | create or edit an issue with explicit parameters (`project_id`, `title`, `category`, `severity`, `symptom`, `root_cause`, `owner_actor_id`, `id`, `expected_version`, `reason`) |
| `ledger_spec_save` | author | create or edit a specification (`project_id`, `parent_id`, `title`, `summary`, `acceptance_criteria`, `priority`, `owner_actor_id`, `origin_ref`, `origin_quote`, `id`, `expected_version`, `reason`) |
| `ledger_task_save` | author | create or edit a task (`project_id`, `title`, `purpose`, `success_condition`, `owner_actor_id`, `origin_ref`, `origin_quote`, `id`, `expected_version`, `reason`) |
| `ledger_criterion_save` | author | create or edit a milestone criterion (`project_id`, `id`, `milestone_name`, `goal_label`, `code`, `criterion_text`, `expected_version`, `reason`) |
| `ledger_transition` | author | change author-side status or state (`OPEN`, `RESOLVED`, `NOT_AN_ISSUE`, `DISPUTED`, `MERGED`, `DRAFT`, `PLANNED`, `ACTIVE`, `BLOCKED`, `COMPLETED`, `CANCELLED`, `UNKNOWN`) |
| `ledger_record_deliverable` | author | record or amend a deliverable (`document`, `commit`, `media`, `scene`, `dataset`, `design`, `artifact`, `other`) on an `issue`, `spec` or `task`, optionally marking it `RESOLVED` or `COMPLETED` (`mark_done`) |
| `ledger_record_case` | author | add an observed case under an issue (`MERGED` issues are refused and name their target) |
| `ledger_link` | author | link issues, specs, tasks and criteria (`issue_task`, `issue_spec`, `spec_task`, `criterion_task`, `criterion_issue`, `criterion_spec`, or issue `RELATION_KIND` codes), or remove a link |
| `ledger_correct` | author | mark a history row as wrong |
| `ledger_batch` | author | up to 200 author operations in one transaction, including project version changes; one `operationKey` for the batch |
| `ledger_review` | reviewer | independent verification (`VERIFIED`, `MET`, `NOT_MET`) and reopening (`evidence_uri` required) |
| `ledger_review_correct` | reviewer | mark a reviewer verification row as wrong |

Attachment tools. Use a session with a live grant to start, seal, inspect a sealed bundle or read it. The authenticated `attachments-send` CLI uses the per-file `uploadId` to send and resume chunks directly.

| Tool | What |
|---|---|
| `artifact_begin` | start the upload of a bundle of 1..64 files (`files`, required `operationKey`, optional `purpose` = `evidence`) |
| `artifact_chunk` | send the next chunk (at most 262144 bytes, base64) with `chunkSha256`; idempotent by `uploadId`, `sequence` and `chunkSha256` (no `operationKey`) |
| `artifact_finish` | verify every declared file size and SHA-256 and seal the attachment bundle; idempotent by `attachmentId` (no `operationKey`) |
| `artifact_status` | inspect the state, size, SHA-256, expiry and progress of an `uploadId` or `attachmentId` (never a path or storage key) |
| `artifact_read` | read one decrypted, integrity-checked page of file `seq` in a sealed attachment |

Read tools (no attestation and no grant; structured queries that replace the raw SQL tools of 1.x):

| Tool | What |
|---|---|
| `ledger_get` | state and row version for up to 200 IDs. Set `include_details=true` for full details across up to 10 mixed issue, spec, task or criterion IDs |
| `ledger_issue` | one issue with deliverables, tasks, specs, criteria, cases (including merged issues), relations, `owner_actor_id` and recent history |
| `ledger_spec` | one specification with summary, acceptance criteria, parent/child specs, deliverables, tasks, issues, criteria and recent history |
| `ledger_task` | one task with purpose, success condition, origin, owner, deliverables and related records. Use `ledger_history` for changes |
| `ledger_criterion` | one milestone criterion with milestone, goal label, code, criterion text, state and linked tasks, issues and specs |
| `ledger_history` | append-only history and receipts by ids, id range, entity, project, operation, author or time range; `include_before` adds `before_data` |
| `ledger_search` | find IDs across record types by keyword or by `project` and `project_version`. Keywords use `ANY` (default) or `ALL` |
| `ledger_issues` | issues of a project, filtered by status, severity, category, attention kind, owner and `project_version` |
| `ledger_specs` | specifications by status, parent ID, priority, owner, attention or `project_version` |
| `ledger_tasks` | tasks by state, owner, attention or `project_version` |
| `ledger_criteria` | release criteria by state, `milestone_name` or `project_version` |
| `ledger_attention` | items needing action: `OPEN_WITH_DELIVERABLE`, `RESOLVED_WITHOUT_DELIVERABLE`, `OPEN_NO_OWNER`, `SPEC_COMPLETED_NOT_VERIFIED`, `TASK_UNKNOWN`, `DONE_NOT_VERIFIED` |
| `ledger_stats` | counts per state and severity of one project or of all; `bucket` = `day` adds new items per UTC day |
| `ledger_projects` | projects by stable `project` ID or case-insensitive `display_name` substring. Returns the stable `id`, record ID prefix `code`, description `title` and current `display_name`. Use `include_versions=true` for version names |
| `ledger_codes` | common codes; retired codes included with `include_inactive` |

The list read tools take `max_rows` (1 to 1000, default 50) and `cursor`; their answers carry `truncated` and `next_cursor`. `ledger_search` applies `created_from` and `created_to` to issues, specs and tasks; a criterion has no creation time, so a search with either bound leaves criteria out (a search for criteria only with a bound returns nothing).
The tools `execute_sql_reader` and `search_objects_reader` of 1.x were removed: use the read tools above.

All MCP tools accept `fields` to return up to 16 selected paths inside `data`. For example, use
`["items.id","items.state","items.version","not_found"]` with `ledger_get` before a bulk write.
Paths apply to each array element. Selecting `items` on a page also returns `truncated` and `next_cursor`.
`ledger_get` retains `not_found`. Attachment content retains its byte-range information.
Use `structured_only=true` when the client consumes MCP `structuredContent`. The default also includes the JSON text copy.
For author writes sharing a reason, provide `default_reason` once in `ledger_batch`. Items can override it with
their own `reason`. For attachment text, request `["text","size","totalBytes"]` with the needed byte range.

Use `ledger_batch` with `op=project_version` and `version_create` to register a version. Renaming it or changing its
description requires `version_update`. For existing records, pass `link_items` or `unlink_items` grouped by kind:

```json
{"op":"project_version","project_id":"studio","name":"Review 2","link_items":{"spec":["STUDIO-S-0001"],"task":["STUDIO-T-0001"]}}
```

Use `create=true` to register the version, or `original_name` to rename it. Each direction accepts at most 200 record IDs
from the same project. Linking requires `issue_update`, `spec_update`, `task_update` or `criterion_update` for the
respective record type. Existing callers can still use `link_issue_ids` and `unlink_issue_ids`. Supply `reason` on the operation
or `default_reason` on the batch. To create a record and connect it at once, use `op=save` with `project_version`.

Use `ledger_projects(project=..., include_versions=true)` to retrieve exact version names. Use `ledger_search` with
`project` and `project_version` to list all linked record types without keywords, or use the corresponding list tool to
filter a single type. Retrieve known IDs and their version links with `ledger_get(include_details=true)`.
Use `ledger_link` for relationships between two ledger records.

Use `ledger_search` to find unknown records, `ledger_get` for known IDs, and `ledger_attention` for the follow-up
queue. Set `include_context=true` on the queue to fetch titles, owners and update times in the same call. Use
`ledger_stats` for totals. `ledger_whoami(project=...)` filters grants to one project. `can_verify_login` describes the
session. The project grant and reviewer independence are checked by `ledger_review`.

When a common code changes, the server sends `notifications/tools/list_changed`, so a connected client reloads the tool
definitions without a restart. A change made by another process (the admin web, `ledger-cli`) is noticed within
`ledger.mcp.code-poll-interval`. A new tool is registered when the server starts.

`ledger_record_case` takes `issue_id`, `observation` (1 to 4000 characters) and `reason`, and optionally `evidence`,
`source_quote` and `observed_at` (ISO-8601 with `Z` or an offset). In `ledger_batch` the same arguments go in an operation
`{"op": "record_case", ...}`:

```json
{"issue_id": "WEB-I-0007", "observation": "checkout returns 500 for a gift card",
 "evidence": "request 7f3a, api/checkout.ts:88", "source_quote": "I can't pay with my gift card",
 "observed_at": "2026-11-05T10:00:00Z", "reason": "second report of the same failure"}
```

## Input, output, and errors

- Use the connected server's `tools/list` schemas for the exact parameter names, JSON types, and required fields. The tool tables above describe each operation and its scope.
- When `ok` is `true`, read the tool's result in `data`. When `ok` is `false`, read `error.code` for the stable error token, `error.details` for context, and `error.issues` for field errors. A field error gives its `path`, `reason`, and `allowed` values where applicable.
- Read tools require the transport credential. `ledger_whoami` also needs the host identity. Author writes require a live grant for the operation on each affected project. Reviewer writes additionally enforce the reviewer rules. See [Ledger concepts](concepts.md#concepts).

| Error code | Handle it by |
|---|---|
| `VALIDATION_FAILED` | Correct the fields identified in `error.issues`. |
| `HOST_ATTESTATION_REQUIRED` | Confirm the host added the session proof. See the [agent integration checklist](client-hooks.md#verify-the-connection). |
| `SESSION_NOT_GRANTED` | Confirm the session's live scope on the target project. |
| `VERSION_CONFLICT` | Read the latest row version and review the changes before trying again. |
| `OPERATION_CONFLICT` | Reuse the existing key only with the original request. Use a fresh key for a different write. |
| `VERIFY_NEEDS_REVIEWER_LOGIN` | Submit through the reviewer channel with its required permission. |
| `SAME_SESSION` | Assign a reviewer who meets the independence requirements for the record. |

## Attachments

A review report (or any other evidence) is kept as an attachment, never as text in the database and never as a local file that
can be edited without a trace. The history row of a record points to it with the optional `attachment_id` of `ledger_transition`,
`ledger_review`, `ledger_review_correct`, `ledger_correct`, `ledger_record_deliverable`, `ledger_record_case` and the same operations of
`ledger_batch`. The record is written only when the attachment exists and was uploaded by the author who writes the record.

- **Upload.** Uploads are made through the five MCP tools above: `artifact_begin` (with a required `operationKey` and an
  ordered `files` list of 1..64 files), `artifact_chunk` for each chunk of at most 262144 bytes, `artifact_finish` (with
  `attachmentId`), and `artifact_status` and `artifact_read` (with optional `seq`, default `0`) to inspect or read a file.
  To upload local files without sending raw bytes through the model context, measure them locally with
  `ledger-cli attachments-prepare <file> [--name=<name>]` and stream chunks directly to `/mcp` with
  `ledger-cli attachments-send <file> --upload-id=<id>` (uses `uploadId` capability without session attestation).
- **Storage.** The database holds only bundle/file metadata and storage copy revisions (`upload_staging`, `attachment`,
  `attachment_file`, `attachment_storage`; no body column, no foreign key). Each file copy is under `ledger.attachments.directory`,
  named by 128 random bits, with no extension and no original name, encrypted with AES-256-GCM in segments (one per chunk, the
  storage key and the position are authenticated), and read only once finished. An attachment is never changed or deleted: the
  application account of the database has `SELECT` and `INSERT` on it only.
- **Keys.** `setup` creates the first 256-bit key file in `ledger.attachments.key-directory` (readable by the service account
  only) and sets `ledger.attachments.active-key-id`. To rotate: `ledger-cli attachments-key-create`, set `active-key-id` to the new id,
  restart, then `ledger-cli attachments-rekey --from=<old id>` writes the files of the old key again; an old key file is kept while a
  current file or an open upload uses it. The start check names the key id (never the key) when a needed key file is missing.
- **Errors.** `ARTIFACT_CHUNK_CONFLICT`, `CHUNK_HASH_MISMATCH`, `ARTIFACT_BUSY` (send again later), `ARTIFACT_INCOMPLETE`,
  `ARTIFACT_HASH_MISMATCH` (the bytes are kept), `ARTIFACT_CORRUPTED`, `ARTIFACT_LIMIT`, `ARTIFACT_UNSUPPORTED_PURPOSE`,
  `ARTIFACT_EXPIRED`, `ARTIFACT_NOT_FOUND`, `ARTIFACT_OPERATION_CONFLICT`, `ARTIFACT_STORAGE_FULL` (the disk is too full: the owner
  has to free it), `ARTIFACT_STORAGE_IO_ERROR`, `ARTIFACT_STORAGE_UNSAFE` (the storage path cannot be used for this purpose: a file where the folder belongs, a folder where a file belongs, or it cannot be made owner-only on this file system or for this account), `ARTIFACT_KEY_MISSING` (no usable key: the key file is missing, empty or not 256 bits long; restore it). Every error of these tools
  carries `details.retryable`, `true` only when the same request sent later can succeed (`ARTIFACT_BUSY`, and `ARTIFACT_LIMIT`
  with `details.limit` `staging-budget` or `open-uploads`); `ARTIFACT_INCOMPLETE` is `false` (send the missing chunk, then finish).
- **Check.** `ledger-cli attachments-check` reports the attachments that are missing or damaged (never deleted) and those no
  history row points to (kept), and moves a file that no row names, or that a rekey replaced, to `orphans/` once
  `orphan-grace` has passed; `--purge-orphans` is the only way `orphans/` is emptied, and it never deletes a file whose row exists again (audited). The admin web shows the same result
  read only, and shows a text attachment escaped on the page of the record and offers any other type as a download through the
  application.
- **Backup.** Back up and restore the attachment directory, the key directory and the database together. After a restore run
  `ledger-cli attachments-check`: it has to report no missing and no damaged attachment. The check moves a quarantined file back
  when its row exists again.
- **Install.** The installer creates the attachment directory (owned by the service account, closed to every other account), the key
  directory below the secret directory, and the settings. 1.x evidence stays as the URI and digest it was recorded with: the import
  fetches nothing.
