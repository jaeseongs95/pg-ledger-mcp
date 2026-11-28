# Ledger concepts

[한국어](concepts.ko.md) · [Guide index](guide.md)

## Concepts

- **Author identity is stamped by the host hook, not claimed by the model.** Every MCP request authenticates over HTTP with the
  configured `Bearer` token `ledger.mcp.token`, and every write call and `ledger_whoami` carries the reserved key `_hostAttestation`
  set to `<vendor>:<session-id>`, which the client host hook stamps and models must omit. For example, the author is `claude:1f2e3d4c`
  or `codex:9a8b7c6d`; the owner of the ledger is `user:owner`. A tool has no `author_id` argument.
- **Writes need a live grant.** The owner grants an author a scope on a project with `ledger-cli grant` (or on the grants page of
  the admin web). The scopes are `issue`, `spec`, `task`, `deliverable` and `verify`. A write without a matching grant is refused. A
  write that touches rows of two projects (a spec, task or criterion moved to another project, a link, a merge into an issue of another
  project) needs the matching grant on both projects.
- **Completion and verification are separate across all entities.** Authors record deliverables (`ledger_record_deliverable`) on an
  `issue`, `spec` or `task` and report completion (`RESOLVED` on an `issue`, `COMPLETED` on a `spec`, `task` or criterion). `VERIFIED`,
  `MET` and `NOT_MET` are set only through the reviewer tools `ledger_review` and `ledger_review_correct`, which need the scope
  `verify` and an `evidence_uri`. An issue is verified only with at least one recorded deliverable and only by a session that did not
  register the issue, did not record a case of it and did not record or amend a deliverable of it (merged issues count as its own); a
  specification or task is verified only by a session that did not create it, does not own it, never recorded a deliverable on it and
  never reported it completed; a milestone criterion is set `MET` or `NOT_MET` only by a session that did not register or complete it
  (otherwise `SAME_SESSION`). Verification and rework follow-ups route to `owner_actor_id` rather than `created_by`. Check metadata
  (`check_ref`, `check_result`) is optional and vendor-neutral. A `VERIFIED` entity takes no `ledger_record_deliverable` and no edit
  (`VERIFIED_ENTITY`): a reviewer reopens it first with `ledger_review`.
- **History is append-only.** Every write adds a history row: when, author, channel, operation, state change and reason. The
  application has no path that updates or deletes a history row, and the grant template of the database module (`migrate` applies it to
  the application account) gives that account no `UPDATE` or `DELETE` on it. A mistake is marked with a `CORRECT` row. An operation
  that changes an existing row also stores the full row as it was before the change (`before_data`, returned by `ledger_history` with
  `include_before`). One exception: a `ledger_record_deliverable` that changes nothing of the target entity except its `updated_at` and
  version (the owner and the status stay) writes no extra history row of the target entity and so stores no `before_data` of it; the
  deliverable row is its history.
- **Retries are safe.** Every write tool takes a required `operationKey` (1 to 128 characters, for example a UUID). Repeating the same
  request with the same key returns the stored result and writes nothing twice. The keys belong to the author (as the keys of
  `artifact_begin` do): another author may use the same key for a write of its own. A key of the author that is already bound to
  another operation, payload or channel is refused with `OPERATION_CONFLICT`. The server never makes a key up: a missing or empty
  key is refused with `VALIDATION_FAILED` (path `operationKey`). `ledger_batch` takes one key for the whole batch and the batch is
  one request: the same key with the same operations returns the stored results, the same key with other operations (one added,
  removed, changed or moved) or under another tool is `OPERATION_CONFLICT`. `artifact_chunk` and
  `artifact_finish` take no `operationKey`: an upload starts with the `operationKey` of `artifact_begin`, a chunk is idempotent by its
  `uploadId`, `sequence` and `chunkSha256`, and finish by its `attachmentId`.
- **Optimistic locking.** Edits and transitions carry `expected_version`; a stale version is `VERSION_CONFLICT`.
- **Projects and ids.** An issue belongs to a project and gets the id `<CODE>-I-0001`, `<CODE>-I-0002`, and so on (imported 1.x rows
  keep `<CODE>-D-0001`). A specification without an id gets `<CODE>-S-0001`, and a task without an id gets `<CODE>-T-0001`. An id that
  a caller chooses for a specification, task or milestone criterion is 1 to 120 characters without whitespace, in any case and with any
  characters (`:`, `/`, `#`, `.`, `_`, `-` and so on); an id that equals an existing one when the case is ignored is refused
  (`DUPLICATE_KEY`), and an id is always compared exactly. An issue id that a caller chooses, and a spec or task id in the numbered form
  `<CODE>-S-0001` or `<CODE>-T-0001`, must have a number that is not above the next number of the project: a free number below it is
  accepted, and a number above it is refused with `ID_NUMBER_AHEAD` (`ID_COUNTER_CHANGED` tells you to send the request again if
  another request took the number meanwhile). Numbered ids use at least 4 digits with no leading extra zeros: `<CODE>-I-00002` and
  number 0 are refused (`VALIDATION_FAILED` at `id`).
- **One issue, one root cause, many observed cases.** Each observation of an issue (symptom, log, field note, report) is a case under it
  (`ledger_record_case`). A merged issue (`MERGED`, `merged_into`) keeps its cases; `ledger_issue` shows them under the issue it was
  merged into, and a `MERGED` issue takes no new cases.
- **Common codes.** Issue category, issue severity, deliverable kind and relation kind are the four groups `ISSUE_CATEGORY`,
  `ISSUE_SEVERITY`, `DELIVERABLE_KIND` and `RELATION_KIND`, with Korean and English names. The active values are part of the tool
  definitions. A retired code stays on existing rows and is refused when newly assigned. A severity is required on every issue. Only a
  write that stores a common code checks it; a transition does not, so an imported issue that still holds the retired `UNRATED` can be
  progressed and verified, and is rated by an edit (`ledger_issue_save`). A relation kind has a direction, which is part of its code:
  `caused_by` and `blocks` are directed (`GIVEN`), keep `a` and `b` in the order the caller gave them, and so (a, b) and (b, a) are two
  relations; `same_site`, `same_family`, `same_work_unit`, `same_fix_unit` and `regression_of` have no direction (`CANONICAL`) and are
  one relation in either order (`ledger_codes` shows the direction, and `code-set` sets it).
- **Creation provenance is immutable.** New rows receive `created_by` automatically; edits and reviewer transitions keep it.
- **Time is UTC.** Timestamps are stored and transmitted in UTC (ISO-8601 with `Z`); the server converts no time zone. The JVM and
  the database session must run in UTC, and a server that finds otherwise does not start (`ledger.schema.require-utc`). Each program
  sets the default time zone of its own JVM to UTC when it starts, so a command given as in this README needs no
  `-Duser.timezone=UTC` whatever time zone the host has (the service definitions of the installers pass the option as well).
- **ANSI SQL.** The product does not bundle a JDBC driver: the user obtains the JDBC driver of the database of their choice
  and registers it with `driver-register`. The SQL is ANSI and uses the JDBC standard, and no DBMS-specific feature is used: the
  schema files have no triggers, procedures, views, sequences, identity columns or boolean columns, and the rules that the database
  used to enforce are enforced by the application services. Three places differ by product: the row limit of a list, the
  declaration of the long text column type and the probe that proves the encoding of the database, and `SqlBuilder` chooses all three from the product of the connection. PostgreSQL is the
  database that is tested and guaranteed in this edition. Other products may work but are not tested, and outside these three places
  this edition contains no tailoring for a particular product (collations, session settings). The database must use UTF-8 and compare text case-sensitively.
  `migrate` proves the encoding before it changes anything: it sends a text of three characters (two Korean letters and an emoji) through a query of the dialect and
  refuses to go on when the database gives back another length or another text; it does not read the list of character sets of the database.
  Where a database keeps a default character set for each schema and a character set for the server as well as one for the connection (MariaDB
  and MySQL do), all three must be utf8mb4: the default character set of the database of the JDBC URL (the schema that is checked), the variable
  character_set_server and the character set of the connection. `migrate` refuses to go on, before it changes anything, when the first two are not.

A direct MCP caller is authenticated on HTTP by the configured `Bearer` token `ledger.mcp.token`, paired with the host-stamped `_hostAttestation`.
