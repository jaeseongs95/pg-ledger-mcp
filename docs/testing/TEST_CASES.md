# pg-ledger-mcp (Java 25 / Spring Boot rewrite): Test-Case Catalogue

Status: written BEFORE any implementation. Design authority: the approved plan (`luminous-baking-garden.md`) plus the later user decisions listed in section 1.4. No case in this file is a Node port: every old scenario is restated as the behaviour the user relies on, then expressed against the new design.

## 1. Purpose, conventions, levels

### 1.1 Purpose
1. Fix, before coding, what "done" means for each module (domain, application, schema, MCP server, admin web, CLI, hooks, e2e).
2. Carry over every behaviour that the old suite (`tests/e2e.mjs`, `tests/e2e.sh`, `tests/upgrade-atomicity.sh`, `tests/admin-ui.mjs`, `tests/claude-hook.mjs`, `tests/host-attestation.mjs`) protected, restated for the new design; nothing is silently omitted (section 18 reconciles every old ID).
3. Add the cases the plan and the later user decisions require (read tools, server-assigned ids, `list_changed`, receipts, `before_data`, UTC, driver registration, V-file schema runner, ArchUnit, ANSI check, secret store, legacy import).

### 1.2 IDs
`<AREA>-<NNN>`; service cases add a group: `SVC-<GROUP>-<NN>`. Areas: `DOM` domain, `REPO` repository, `SCH` schema runner, `DRV` JDBC driver registration, `SVC` application services, `MCW` MCP write-tool contract, `MCR` MCP read-tool contract, `MCC` MCP catalogue/transport/security, `ADM` admin web, `CLI` CLI, `SEC` secret store, `HK` hooks, `E2E` deployed, `ARC` ArchUnit, `ANSI` static check, `MIG` legacy import. IDs are stable; a retired case keeps its ID and is marked `dropped`.
Old IDs (Source column) are the labels used in section 18 (`e2e.mjs:C1`, `e2e.sh:S-U07`, `admin-ui:A4`, ...).

### 1.3 Levels
| Level | Meaning | Runner |
|---|---|---|
| UNIT | Domain records, enums, policies, codecs. No Spring, no DB. | JUnit 6 |
| REPO | Spring Data JDBC repositories and direct ANSI SQL against a real database. | JUnit 6, embedded H2 (PostgreSQL at the test server stage, see 1.4a) |
| SERVICE | Application services in a Spring context (`@Transactional`, `WriteGate`, receipts, ids). | JUnit 6, H2 default, PG where marked |
| MCP | MCP contract through the MCP Java SDK client against the Boot server (`/mcp`, Streamable HTTP). | JUnit 6 |
| ADMIN | Admin web (MockMvc/HTTP, Spring Security chain). | JUnit 6 |
| CLI | `ledger-cli` commands in-process with captured streams. | JUnit 6 |
| HOOK | The one executable jar `ledger-client-hook` run as `java -jar ledger-client-hook.jar <claude\|codex\|antigravity>` with synthetic host JSON. | JUnit 6 (spawns the JDK launcher only) |
| E2E | Deployed server(s) on the test host, MCP Java SDK client and HTTP. | `ledger-e2e` |
| ARCH | ArchUnit rules and source/dependency scans. | JUnit 6 |
| ANSI | Static check of repository SQL strings and every `V{n}__*.sql` file. | JUnit 6 |
| MIGRATION | Legacy `ledger` schema import and V-file schema runner. | JUnit 6 |

### 1.4 DBMS matrix column (changed by user decision)
Only **PostgreSQL (embedded)** and **H2** are tested. `yes` = the case runs on both; `no` = runs once on the default database (H2). There is no list of "supported DBMS" anywhere: documentation states only that a JDBC driver can be attached, that SQL is ANSI only and that no DBMS-specific feature is used (case ARC-031 checks the docs). MariaDB/MySQL/Oracle/SQL Server are out of scope and every old or planned case that depended on them was removed.

Later user decisions that this catalogue applies (they supersede the plan where they differ):
1. **No Liquibase.** Schema = ANSI DDL files `V{n}__*.sql` applied by `ResourceDatabasePopulator` with our own history table `schema_history(version, file_name, sha256, applied_at, outcome SUCCESS/FAILED)`. No IDENTITY, no sequences: every surrogate key (history, fix, case, grant) is allocated from counter rows by `IdAllocator`.
2. **No JDBC driver is bundled.** A driver jar path + driver class are registered through admin web or the CLI command `driver-register`, stored with the jar SHA-256 in `/etc/<name>/jdbc-driver.yaml` (mode 0640, readable by the service user, read through `spring.config.import`), applied after restart, loaded `URLClassLoader` -> `Driver` -> `SimpleDriverDataSource` -> Hikari. `DriverManager` is never used.
3. **`history.before_data`:** every operation that modifies an existing row stores the full previous row (JSON) in the same transaction; CREATE has none; failed requests leave no history; `ledger_history` returns it only with `include_before=true`; imported rows keep it empty.
4. **UTC everywhere.** All time columns hold UTC; MCP and admin web emit ISO-8601 with `Z`; the admin HTML carries `<time datetime="...Z">` and the browser converts for display; the server never converts zones.
5. **No boolean columns** (enums instead: `common_code.state` ACTIVE/INACTIVE, `defect_fix.origin` NATIVE/IMPORTED). JSON booleans that are part of the external tool contract (`remove`, `mark_source_fixed`, `removed`, `truncated`, `author_format_ok`, `can_verify_login`) stay; they are mapped at the boundary.
6. **Empty strings** are normalised to NULL or rejected in Java, per field (rule table in SVC-NORM).

7. **Second-round decisions of the main agent** (applied in this version; they resolve the former "unclassified" list): (a) deploy script keeps nginx reverse-proxy config and optional cloudflared ingress, admin native HTTPS through a Spring Boot SSL bundle; (b) `one-click-install.sh` stays a thin OS wrapper (OS prepare, `ledger-cli migrate`, `secret-set`), the argv secret option `--google-client-secret` is removed; (c) `install.sh` on PostgreSQL takes a DB dump before `migrate`; (d) admin overview MCP health is a Spring `RestClient` GET of the configured MCP `/healthz` URL; (e) CSRF is the Spring Security token plus a `SameSite=Strict` session cookie, the old `Sec-Fetch-Site`/`Origin` checks are dropped; (f) a FAILED schema file is retried by the next `migrate`, every attempt appends a `schema_history` row, the latest outcome of each version must be SUCCESS before the next version; (g) without a registered driver the MCP server and every CLI command except `driver-register` fail fast, the admin starts in setup mode (login + driver registration only); (h) `ledger_search` match is `ANY` (default) or `ALL`; (i) imported receipts are flagged LEGACY and a replay with such a request id is refused with `REQUEST_ID_LEGACY`; (j) legacy `''` in NOT NULL columns: the import reports the rows and stops; (k) `category-add` dropped (`code-set` covers it); (l) three questionable behaviours stay as they are ("unchanged by design"); (m) plan fixes: grant check `revoked_at IS NULL AND (expires_at IS NULL OR expires_at > ?)`, case-versus-merge serialisation by `UPDATE defect SET case_seq = case_seq + 1 WHERE id = ? AND status <> 'MERGED'`, entity ids accepted only by an upper-case regex (case sensitivity then does not depend on the DBMS), hooks are one executable jar module `ledger-client-hook` (Jackson inside, first argument = client enum), not single-file sources.

Marker `(P)` in a Then cell = provisional contract detail (key name, token name or semantic choice not fixed by the plan). The semantic assertion is mandatory; the literal may be renamed once the contract is fixed, in one edit of the constants the tests reference.

### 1.4a Test DB reduction (user decision 2026-11-11: H2 only)
The module tests run on the embedded H2 in default mode only. Embedded PostgreSQL (zonky), the PostgreSQL test driver and the DBMS selection enum of the test support are removed; reason: "to use the product properly the user installs a real PostgreSQL anyway" (user, 2026-11-11). Every `yes` in the DBMS column therefore means: the assertion runs on H2 in the module tests, and the PostgreSQL side moves to the test server stage (a real DBMS with the driver attached). Cases whose point exists only with PostgreSQL (or with two DBMS) are not run by the module tests and are listed here so that no case disappears silently. Where the same assertion holds on H2 it is kept.

| ID | Status | What moved to the test server stage |
|---|---|---|
| REPO-037 | moved: test server stage (module test removed) | prepare every captured SQL string on a second DBMS; needs two databases |
| SCH-013 | moved: test server stage (module test removed) | compare the logical schema of PostgreSQL and H2 |
| SCH-004 | H2 part kept; PostgreSQL part moved | transactional DDL: no partial V3 objects on PostgreSQL |
| DRV-006, DRV-007, DRV-011 | H2 part kept; PostgreSQL part moved | the chain and the smoke run with the PostgreSQL driver jar |
| REPO-023, REPO-024, REPO-038, SCH-014 | H2 part kept; PostgreSQL part moved | refusal for the least-privilege account with SQLState 42501 and the PostgreSQL role set-up |
| SVC-TZ-01, SVC-TZ-02, REPO-029, SCH-019 | H2 part kept; PostgreSQL part moved | the session time zone of a PostgreSQL session |
| SCH-001 .. SCH-011, SCH-015, SCH-020, SCH-021, REPO-001 .. REPO-038 (the rest) | H2 part kept; PostgreSQL run moved | the second run of every `yes` case on PostgreSQL |
| E2E-014, MIG-025, ANSI-004, ANSI-013 | PostgreSQL run is a test server stage case | unchanged text; not run by the module tests |

Test support of the application module (SERVICE level, §6 and §8): the service cases run in a Spring Boot test context (`@SpringBootTest` through the test annotation `LedgerServiceTest`) on an in-memory H2 database that Spring Boot configures from `spring.datasource.*`; the schema is built by the real `V{n}__*.sql` files and the real migrator before the first test, and Spring's test context cache shares the one context between the test classes. No transaction wraps a test method (the test classes are not `@Transactional`, and the parameter resolver refuses a test that would run in one), so the race cases (SVC-RACE, SVC-RCP-09..11, SVC-GATE-18, SVC-GRT-06, SVC-BEF-08/09) run real threads that commit. The variants that need another context (database session in another time zone: SVC-TZ-02; a fresh installation without categories: SVC-CC-10; a restart of the application on the same database: SVC-RCP-13) are combinations of configuration and properties of `ApplicationContextRunner`.

### 1.5 Error-token registry
Tokens are the external contract (MCP tool error `code`, admin `ProblemDetail.code`). MCP tool errors return `isError=true`, `structuredContent{code,message,details}`, and the text content starts with `<CODE>: <message>` so that text-matching clients keep working.
| Token | Origin | Used for |
|---|---|---|
| `HOST_ATTESTATION_REQUIRED` | carried (sql `_begin`, `whoami`) | `_hostAttestation` absent/null |
| `AUTHOR_ID_FORMAT` | carried | bad `<vendor>:<session>` / `user:owner` format, also `owner_actor_id` |
| `SESSION_NOT_GRANTED` | carried | no live grant for author+project+scope |
| `VERIFY_NEEDS_REVIEWER_LOGIN` | carried | verify-scope operation through the AUTHOR channel |
| `CODE_REQUIRED` / `CODE_INVALID` | carried (trigger `_check_codes`) | common-code columns; details list valid codes |
| `SAME_SESSION` | carried | verifier session equals fixer / task owner / task reporter session |
| `MERGED_DEFECT` | carried | case on a merged defect; message names the merge target |
| `VERSION_CONFLICT` | plan | expected version differs; message has expected and current |
| `AGENT_TOKEN_FORBIDDEN`, `UNAUTHORIZED`, `DNS_REBINDING_BLOCKED`, `TOO_MANY_AUTH_FAILURES`, `COMMON_CODE_DELETE_FORBIDDEN`, `PAYLOAD_TOO_LARGE` | carried (admin server) | admin web |
| `VALIDATION_FAILED` (P) | new (replaces zod text, DB CHECK text, `INVALID_*` admin tokens) | structure/format/enum/unknown argument; `details[]{path,reason,allowed?}` |
| `REQUEST_ID_LEGACY` | new (decision 7i) | replay with a request id of an imported (LEGACY) receipt; never returns the stored response |
| `REASON_INVALID` (P) | new (sql `_check_reason`) | reason not 10..1000 chars after trim |
| `REQUEST_CONFLICT` (P) | new (sql "already bound") | same request id, different operation/payload/author/channel |
| `NOT_FOUND` (P) | new ("not found") | unknown id, project, history id, merge source |
| `DUPLICATE_KEY` (P) | new (raw PK/UNIQUE violation) | explicit id exists, condition (project,release,code) exists |
| `ID_PREFIX_MISMATCH` (P) | new ("must start with") | defect id not `<CODE>-D-` |
| `PROJECT_MOVE_FORBIDDEN` (P) | new ("cannot move") | defect project change |
| `NOTHING_CHANGES` (P) | new ("nothing changes", "nothing changed") | no-op save, no-op fix update, duplicate link, remove of absent link |
| `TRANSITION_INVALID` (P) | new | same state, forbidden source state, task to UNKNOWN |
| `FIX_REQUIRED` (P) / `FIX_ALREADY_RECORDED` (P) | new | SOURCE_FIXED/VERIFIED without fix; fix re-recorded without CI data |
| `EVIDENCE_REQUIRED` (P) | new | service-level guard when evidence is missing |
| `MERGE_TARGET_INVALID` (P) | new | missing, self or already merged target |
| `CSRF_REJECTED` (P) | new | admin CSRF failure |
| `DRIVER_NOT_CONFIGURED`, `DRIVER_INVALID` (P) | new | driver registration |
| `INTERNAL_ERROR`, `INTEGRITY_VIOLATION` (P) | new | unexpected failure, DB integrity error; never contains SQL or vendor text |
| `LOGIN_NOT_ALLOWED` | dropped | role logins no longer exist |

### 1.6 Old-behaviour legend
Old checks without an own label in the source are labelled in file order: `E-01..` (`e2e.mjs`), `S-<group><nn>` (`e2e.sh`: U upgrade, F fresh, O orchestration, R race, D database, C constraint, I install, W web/domain, X data). `A1..A12` are the 12 `admin-ui.mjs` checks in file order, `CH-01..` the `claude-hook.mjs` tests, `S01..S14` the `host-attestation.mjs` tests. Section 14 lists them all.

## 2. DOM: domain (UNIT, no DB)

| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| DOM-001 | UNIT | `AuthorId` candidates `claude:1234abcd`, `a:x`, `user:owner`, vendor of 32 chars, session of 128 chars `[A-Za-z0-9._-]` | parse | accepted | sql `_author_ok`; e2e.mjs:E-06 | no |
| DOM-002 | UNIT | `claude`, `user:someone`, `Claude:x`, `:x`, `a:`, vendor of 33 chars, session of 129 chars, session with space/colon/Korean, `user:owner ` | parse | rejected with `AUTHOR_ID_FORMAT`; absent (null) is a different failure (`HOST_ATTESTATION_REQUIRED`), empty string is `AUTHOR_ID_FORMAT` | e2e.mjs:E-04,E-05,H2 | no |
| DOM-003 | UNIT | `SessionKey` of `agent:claude:1`, `claude:1`, `codex:1` | compare | first two equal (imported `agent:` prefix stripped), third differs; vendor is part of the key | sql `_sid` | no |
| DOM-004 | UNIT | project codes `E2E`, `A`, 32-char code, 33-char, lowercase, leading digit, `A-B` | `ProjectCode.of` | first three valid, others `VALIDATION_FAILED` (`^[A-Z][A-Z0-9_]{0,31}$`) | 10_tables project.code CHECK | no |
| DOM-005 | UNIT | `DefectId` `E2E-D-0001`, `E2E-D-10000`, `E2E-D-001`, `e2e-D-0001`, `E2E-D-` | parse / `DefectId.of(code,n)` | first two valid (4-digit zero pad, grows past 9999), rest rejected (lower-case also by DOM-036); project code is derivable from the id | 10_tables defect.id CHECK | no |
| DOM-006 | UNIT | task ids `E2E-T-0001`, `E2E-T-1`, `E2E-T\|PIPE`, blank | `TaskId.of` | free text allowed (server pattern `<CODE>-T-NNNN` only for issued ids); blank rejected | e2e.mjs:P2 | no |
| DOM-007 | UNIT | `RequestId` non-UUID string / UUID | parse | non-UUID `VALIDATION_FAILED`; derived op id from (batchId, index) is deterministic, differs per index and per batch id | sql batch `md5(p_request:i)` | no |
| DOM-008 | UNIT | reason of 9, 10, 1000, 1001 chars; 9 chars padded with spaces; 10 Korean chars | `Reason.of` | 10..1000 after trim valid (character count, not bytes); others `REASON_INVALID` | sql `_check_reason` | no |
| DOM-009 | UNIT | defect transition policy, every (from,to) over `OPEN, SOURCE_FIXED, VERIFIED, NOT_DEFECT, DISPUTED, MERGED` | evaluate with facts (hasFix, evidence, otherSessionFix, mergeTarget) | same state -> `TRANSITION_INVALID`; table of allowed/denied pairs equals the rules in DOM-010..015; policy is a pure function (no clock, no DB) | sql `transition` | no |
| DOM-010 | UNIT | to `SOURCE_FIXED`, no recorded fix | evaluate | `FIX_REQUIRED` | e2e.mjs:E-15 | no |
| DOM-011 | UNIT | to `VERIFIED` from OPEN / NOT_DEFECT / DISPUTED / MERGED | evaluate | `TRANSITION_INVALID` (VERIFIED only from SOURCE_FIXED) | sql transition | no |
| DOM-012 | UNIT | to `VERIFIED` from SOURCE_FIXED; evidence missing; fix missing; only own-session fix | evaluate in that order | `EVIDENCE_REQUIRED`, then `FIX_REQUIRED`, then `SAME_SESSION`; with an independent-session fix: allowed. Order of checks is asserted (evidence before fix before session) | e2e.mjs:E-17,V3 | no |
| DOM-013 | UNIT | defect leaves `VERIFIED` to any state without evidence | evaluate | `EVIDENCE_REQUIRED`; with evidence allowed; this check runs after the SOURCE_FIXED-needs-fix check | sql transition | no |
| DOM-014 | UNIT | to `NOT_DEFECT` or `DISPUTED` without evidence | evaluate | `EVIDENCE_REQUIRED` | sql transition | no |
| DOM-015 | UNIT | to `MERGED` with target null / self / already MERGED / live | evaluate | first three `MERGE_TARGET_INVALID`; live target allowed; `merged_into` set iff status MERGED (leaving MERGED clears it) | sql transition; e2e.mjs:C8 | no |
| DOM-016 | UNIT | `ScopeResolver` for (entity,from,to) | resolve | `verify` when `to` or `from` in {VERIFIED, MET, NOT_MET}; else `defect` for defect, `task` for task and condition | sql transition v_scope | no |
| DOM-017 | UNIT | task policy over `UNKNOWN, PLANNED, ACTIVE, BLOCKED, REPORTED_DONE, VERIFIED, CANCELLED` | evaluate | to UNKNOWN `TRANSITION_INVALID`; to REPORTED_DONE needs evidence; to VERIFIED only from REPORTED_DONE, needs evidence, owner session and latest reporter session must differ (`SAME_SESSION`); leaving VERIFIED needs evidence | sql transition; e2e.mjs:V7 | no |
| DOM-018 | UNIT | task history list: REPORTED_DONE by A, VERIFIED by B, REPORTED_DONE (from VERIFIED) by B | `ReporterResolver` | reporter is A (latest REPORTED_DONE transition whose from-state is not VERIFIED) | sql transition; e2e.mjs:V7 | no |
| DOM-019 | UNIT | condition policy `UNKNOWN, MET, NOT_MET` | evaluate | to MET/NOT_MET needs evidence; all three involve scope `verify`; leaving MET/NOT_MET to UNKNOWN needs scope verify but (as in the old function) no evidence; unchanged by design | sql transition | no |
| DOM-020 | UNIT | enums `DefectStatus`, `TaskState`, `ConditionState`, `Scope`, `Channel`, `CiConclusion`, `LinkKind`, `CodeState` | parse each constant and an unknown (`FIXED`, `passed`, `related`) | exact value sets equal the old CHECK sets; unknown -> `VALIDATION_FAILED` with `allowed` list | e2e.mjs:K6,K7,K8; S-C01 | no |
| DOM-021 | UNIT | code policy: unknown / null / inactive / active value, column holding legacy inactive value | `CodePolicy.check` | unknown `CODE_INVALID`; null on required code column `CODE_REQUIRED` (severity and category); inactive refused when inserted or changed; retained when unchanged on a column that is not always-active (category); always-active (severity) refused on every write of the row, details flag `holdsLegacyValue=true` | sql `_check_codes`; e2e.mjs:K3,K4,K5; S-U23 | no |
| DOM-022 | UNIT | valid-code list in the error | build | only active codes, ordered by `sort_order` then `code`, in `details.valid` | sql `_check_codes` | no |
| DOM-023 | UNIT | link target forms | canonicalise | relation kinds: `(b,a)` and `(a,b)` give the same key `min\|max\|kind`; `defect_task` id `a\|b`; condition links id `cond\|target`; `a==b` relation -> `VALIDATION_FAILED` | sql link; 10_tables CHECK a<b | no |
| DOM-024 | UNIT | `HistoryEntry` factory | build | TRANSITION iff `to_state` present; CORRECT iff `corrects_id` present; `before_data` absent for CREATE/LINK/CORRECT; IMPORT/LEGACY ops need `legacy_ref`; evidence_sha256 is 64 lowercase hex; record is immutable (no setters, no wither that mutates) | 10_tables history CHECKs | no |
| DOM-025 | UNIT | `BatchOp` JSON | deserialize `op` = save, transition, record_fix, record_case, link, correct; `op` = review / missing / null | six records created with required vs optional keys exactly as old `required`/`allowed` lists; other `op` -> `VALIDATION_FAILED` | sql batch | no |
| DOM-026 | UNIT | `LedgerException` hierarchy | exhaustiveness | every subtype maps to one code enum constant; the single conversion `switch` has no default branch (compile-time exhaustive) | plan: error conversion once | no |
| DOM-027 | UNIT | message bundles `ko` and `en` | compare keys | identical key sets; every code constant has a message in both; no unused key | plan: one `MessageSource` | no |
| DOM-028 | UNIT | payload digest of the normalised input record | compute twice, change key order, pass `fields` as object vs JSON string, omit vs null an optional field, add/remove `author_id` | digests equal in all those cases; differ when reason, id, version, fields or any value differs; a golden SHA-256 for a fixed record is stable across JVMs | sql `_reserve`; plan receipts | no |
| DOM-029 | UNIT | limits table with boundary values: title 1/300/301/0, behavior, root_cause, purpose, success_condition 4000/4001, origin_quote 2000/2001, condition_text 4000/4001, observation 1/4000/4001 and blank, evidence 1/2000/2001, user_quote 2000/2001, grant note 500/501, `ci_ref` blank, `commit_sha` 39/40/41 chars and uppercase, `evidence_sha256` 63/64/65 chars | validate | in-range pass, out-of-range `VALIDATION_FAILED` with path; Korean text counts characters | 10_tables/15_defect_case CHECKs | no |
| DOM-030 | UNIT | create vs edit validation groups | validate `fields` | create requires all NOT NULL columns of the entity; edit allows any non-empty subset of the editable set; `{}` rejected; editable sets: defect `project_id,title,category,severity,behavior,root_cause,owner_actor_id`, task `project_id,title,purpose,success_condition,owner_actor_id,origin_ref,origin_quote`, condition `project_id,release,goal_label,code,condition_text` | sql save `allowed` | no |
| DOM-031 | UNIT | pagination cursor codec | encode/decode, tamper, truncate, cursor of another tool, base64 garbage | round trip exact; opaque (no raw column names); any damage -> `VALIDATION_FAILED`; cursor encodes position (sort key + id) only | plan: cursor | no |
| DOM-032 | UNIT | `MaxRows` | 1, 1000, 50 default, 0, -1, 1001, 50.5 | 1 and 1000 accepted, default 50, others rejected | plan: read tools | no |
| DOM-033 | UNIT | optional-text normaliser | `""`, `"   "`, `null`, `" x "` per field | rules of SVC-NORM-01/02: optional field blank -> NULL; required field blank -> `VALIDATION_FAILED`; no field keeps a non-blank value untrimmed except where the rule says trim | user decision 6 | no |
| DOM-034 | UNIT | UTC time values | `Instant` from `2026-11-05T10:00:00Z`, `...+09:00`, naive `2026-11-05T10:00:00`, `2026-11-05` | `Z` and offset forms yield the same `Instant`; naive local time and date-only -> `VALIDATION_FAILED` (P: ambiguous zone); output formatter always ends with `Z` | user decision 4 | no |
| DOM-036 | UNIT | entity ids (defect, task, condition) `E2E-D-0001`, `E2E-T-1`, `E2E-T\|PIPE`, `OLD-PIPE-C\|A`, `OLD-C-1`, and `e2e-d-0001`, `E2E-t-1`, `Abc`, blank | `EntityId.of` with the single upper-case id regex (P: the constant lives in the domain; it must accept every id used by the old fixtures) | all-upper-case ids accepted; any id containing a lower-case letter -> `VALIDATION_FAILED`; comparison is therefore case-sensitive on every DBMS without a collation rule | decision 7m | no |
| DOM-035 | UNIT | domain aggregates | inspect components | no `boolean`/`Boolean` component (enums instead); no `LocalDateTime`/`ZonedDateTime`/`ZoneId`; all instants are `Instant` | user decisions 4,5 | no |

## 3. REPO: repositories and direct ANSI SQL (PostgreSQL + H2)

All REPO cases run against a schema built by the real `V{n}__*.sql` files (never by hand-made DDL) and the least-privilege DML account unless a case says "owner account".

| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| REPO-001 | REPO | schema from V files | list tables, columns, PK, UNIQUE, FK through JDBC metadata | tables exactly (`defect` carries the `case_seq` counter column, `request_receipt` carries an `origin` enum NATIVE/LEGACY column): project, task, defect, defect_fix, defect_case, defect_task, defect_relation, condition (name may be adjusted by AGS rule), condition_link, session_grant, history, request_receipt, common_code, code_revision, id_counter, schema_history; none of: common_use table (replaced by `@CommonCode`), `_migration`, views, triggers | 10_tables, 15, 16 | yes |
| REPO-002 | REPO | same | scan column types | no BOOLEAN/BIT column anywhere; `common_code.state` and `defect_fix.origin` are enum-valued text with an IN-list CHECK | user decision 5; S-C01 | yes |
| REPO-003 | REPO | same | insert a value outside each fixed set: defect.status, task.state, condition.state, defect_fix.ci_conclusion, session_grant.scope, common_code.state | rejected with SQLState class 23 (CHECK retained as in old `S-C01`) | e2e.sh:S-C01 | yes |
| REPO-004 | REPO | same | inspect CHECK expressions | only IN-lists, comparisons, NULL-ness, `(a IS NULL) = (b IS NOT NULL)` style booleans of columns; no regex, no function call (length rules live in Java) | plan: ANSI only | yes |
| REPO-005 | REPO | `created_by` on project, task, defect, condition, defect_task, defect_relation, condition_link, common_code | read metadata | all eight NOT NULL | e2e.sh:S-C08 | yes |
| REPO-006 | REPO | repository save with `AuditorAware` bound to actor X via `ScopedValue` | insert, then update by actor Y | `created_by`=X after both; `@ReadOnlyProperty` keeps it out of UPDATE statements (SQL spy: no `created_by` in UPDATE) | e2e.sh:S-C04; P1,P5 | yes |
| REPO-007 | REPO | no actor bound | insert | fails loudly (no silent default author) | plan: `AuditorAware` | no |
| REPO-008 | REPO | aggregate with `@Version` | update twice from two stale copies | second fails with optimistic-locking failure; version increments by exactly 1 per update | plan: `@Version`, `VERSION_CONFLICT` | yes |
| REPO-009 | REPO | project counter column | `UPDATE project SET next_defect_no = next_defect_no + 1 WHERE code=?` then read in the same tx | returns previous+1; unknown code updates 0 rows | plan: id allocation | yes |
| REPO-010 | REPO | 32 threads, 20 allocations each, one project | allocate defect numbers concurrently | 640 distinct numbers, set equals 1..640 (no gaps, no duplicates) | plan: concurrent ids | yes |
| REPO-011 | REPO | allocation then `rollback` | allocate again | the rolled-back number is reused; committed numbers stay distinct | plan: counter in same tx | yes |
| REPO-012 | REPO | task counter, two projects | parallel allocation | independent per project; task and defect counters independent | plan: task ids | yes |
| REPO-013 | REPO | `id_counter` rows for history, defect_fix, defect_case, session_grant (no IDENTITY, no sequence) | 64 concurrent writers each inserting one row of each kind | ids distinct per table, no gaps after rollbacks, `ORDER BY id` equals insertion order inside one transaction | user decision 1 | yes |
| REPO-014 | REPO | mixed workload: random writes touching project counter, history counter and rows in varying program order | 100 parallel operations | no deadlock exception surfaces (lock order project counter -> row -> history counter is fixed); all complete | plan: counters | yes |
| REPO-015 | REPO | conditional grant check `UPDATE session_grant SET last_used_at=? WHERE id=? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > ?)` | live, revoked, expired, expiry exactly equal to "now", never-expiring (NULL), other author, other scope, other project | 1 affected row only for live and never-expiring; 0 for the rest; `last_used_at` set to the injected clock. The plan SQL is fixed (decision 7m) to `revoked_at IS NULL AND (expires_at IS NULL OR expires_at > ?)`; NULL = no expiry passes (old `grant` had optional `--expires`) | sql `_begin`; plan: grant check | yes |
| REPO-016 | REPO | grant row locked by tx A's conditional UPDATE | tx B revokes the grant (UPDATE revoked_at) | B waits until A commits (A's write commits before the revocation); after B commits a new check affects 0 rows; reverse order: A sees 0 rows | plan: grant vs revoke; sql FOR SHARE comment | yes |
| REPO-017 | REPO | receipt table with PK request id | two inserts of the same id | second raises a duplicate-key error mapped to `DuplicateKeyException` (SQLState 23) | plan: receipts | yes |
| REPO-018 | REPO | tx A inserted receipt R, not committed | tx B inserts R | B blocks; A commits -> B gets duplicate-key; A rolls back -> B succeeds | plan: duplicates serialise on key wait | yes |
| REPO-019 | REPO | JSON-as-text columns (`history.changes`, `history.before_data`, `request_receipt.response`) | write/read records with Korean, emoji, quotes, newline, backslash, 200 KB body, null elements `[null,"x"]`, `Z` instants | exact round trip through the `@WritingConverter`/`@ReadingConverter` pair; `NULL` stays `NULL` (not `"null"`, not `""`) | plan: CLOB JSON | yes |
| REPO-020 | REPO | `session_grant.active_key` UNIQUE | two live grants with same author/project/scope; revoke the first; grant again; many revoked rows | second live rejected; after revoke (`active_key` = `revoked:<id>`) a new live grant works; many revoked rows coexist | 10_tables session_grant_one_open | yes |
| REPO-021 | REPO | `condition_link.link_key` UNIQUE | duplicate (cond,task); duplicate (cond,defect); cond C with task `X` and with defect `X`; cond `OLD-PIPE-C`+task `A\|T` and cond `OLD-PIPE-C\|A`+task `T` | duplicates rejected; the alias pair coexists; the separator pair coexists (no false collision); NULL columns never in the key | e2e.sh:S-U16..S-U20; 10_tables condition_link | yes |
| REPO-022 | REPO | other UNIQUE/PK/FK | duplicate defect_fix (defect,repo,commit); duplicate (project,release,code); defect_relation `a>=b`; PK (a,b,kind); child without parent for each FK incl. `history.corrects_id`, `defect.merged_into` | rejected with class 23 | 10_tables | yes |
| REPO-023 | REPO | DML account | `UPDATE`, `DELETE`, `TRUNCATE` on history; `UPDATE`/`DELETE` on schema_history | each fails with a permission error; `INSERT` works. Owner account is the only DDL account | e2e.sh:S-D06 (rewritten) | yes |
| REPO-024 | REPO | DML account | `CREATE TABLE`, `DROP TABLE`, `ALTER TABLE` | fail (no DDL privilege) | plan: DML vs owner account | yes |
| REPO-025 | REPO | 30 rows | query with `setMaxRows(n+1)` for n = 1, 10, 29, 30, 1000 | at most n+1 rows returned; no `LIMIT`/`FETCH` in the SQL text | plan: row limit | yes |
| REPO-026 | REPO | 7 rows with identical `created_at` | page size 2 with `created_at < ? OR (created_at = ? AND id < ?)` ascending and descending | every row exactly once, stable order, on both DBs | plan: cursor | yes |
| REPO-027 | REPO | titles `Login Bug`, `login bug`, `LOGIN BUG`, `100%_done`, `a\b` | `LOWER(col) LIKE LOWER(?) ESCAPE '\'` for keywords `login`, `LOGIN`, `100%`, `_done`, `a\b`, injection text `'; DROP TABLE project; --` | ASCII matched case-insensitively; `%`, `_`, `\` are literal; injection text is data (table survives) | plan: ledger_search; ANSI-009 | yes |
| REPO-028 | REPO | rows at `2026-11-04T23:59:59.999999Z`, `...05T00:00:00Z`, `...05T14:59:59Z`, `...05T15:00:00Z` | `BETWEEN` for a UTC day and a KST day (via explicit instants) | inclusive on both ends; row sets exactly as computed from instants (boundary cases around midnight UTC vs midnight KST) | user decision 4 | yes |
| REPO-029 | REPO | JVM default zone `Asia/Seoul` and DB session zone `Asia/Seoul` | insert `Instant 2026-11-05T10:00:00.123456Z`, read raw column text and `Instant` | stored value equals the instant in UTC (raw read shows `10:00:00.123456`), read-back equal; run also with zone `America/New_York`, DST gap/overlap instants (`2026-03-08T07:30:00Z`, `2026-11-01T06:30:00Z`) | user decisions 4, time zone | yes |
| REPO-030 | REPO | DB session zone not UTC | startup guard compares `LOCALTIMESTAMP` with `LocalDateTime` of the UTC `Clock` | guard reports a mismatch (see SVC-TZ-01); equal within tolerance when UTC | user decision: DB UTC | yes |
| REPO-031 | REPO | max-length text columns (4000 Korean characters, 2000, 1000) | insert and read | exact, no truncation (large columns are CLOB-class) | 10_tables | yes |
| REPO-032 | REPO | id candidates differing only by case (`abc-T-1`, `ABC-T-1`) | insert through the service/repository | the lower-case variant is rejected by the id regex before any SQL (DOM-036); only `ABC-T-1` can exist, so no collation difference between PostgreSQL and H2 can matter; a statement spy shows no lower-case id ever reaches the database | decision 7m | yes |
| REPO-033 | REPO | empty-string policy | after running the SVC scenarios, scan every text column | no row holds `''`; optional columns hold NULL | user decision 6 | yes |
| REPO-034 | REPO | defect/task/condition data | `COUNT ... GROUP BY` status, severity, state; attention queries with `EXISTS`/`NOT EXISTS` | counts equal the row set; the five attention kinds appear; `VERIFIED_WITHOUT_CI` is never produced | v_attention; e2e.mjs:V1-att | yes |
| REPO-035 | REPO | `code_revision` single row | 16 threads increment by `UPDATE ... +1` | final value +16; monotonic | plan: `code_revision` | yes |
| REPO-036 | REPO | merge chain A<-B<-C<-...(depth 50) | iterative `merged_into IN (...)` expansion | all 50 descendants found, terminates, no recursive SQL | v_defect `WITH RECURSIVE` (replaced) | yes |
| REPO-037 [moved: test server stage, see 1.4a] | REPO | every distinct SQL string issued by the SVC/MCP/ADMIN suites (captured by a statement spy) | prepare and execute on PostgreSQL and H2 | zero syntax/unsupported-feature errors (SQLState class 42) on either DB | plan: ANSI enforcement (3) | yes |
| REPO-038 | REPO | `before_data` column | `UPDATE` through DML account | denied (immutable like the rest of history) | user decision 3 | yes |
| REPO-039 | REPO | defects `D1` (OPEN) and `D2` (MERGED) | `UPDATE defect SET case_seq = case_seq + 1 WHERE id = ? AND status <> 'MERGED'` | 1 affected row for D1, 0 for D2 (no `FOR SHARE`, no vendor syntax); `version` and `updated_at` of D1 unchanged, `case_seq` +1 | decision 7m (case vs merge) | yes |
| REPO-040 | REPO | tx A bumped `case_seq` of D1 (not committed) | tx B merges D1 (UPDATE of the same row: status, `merged_into`, `version`+1) | B waits for A to commit; after both commit the final row is MERGED with A's bump retained; reverse order: A's conditional UPDATE affects 0 rows after B committed | decision 7m; e2e.sh:S-R01 | yes |
| REPO-041 | REPO | request receipts with `origin` NATIVE and LEGACY | insert/read | enum round trip; no boolean column; LEGACY rows are insertable only by the import path (DML account of the apps cannot set LEGACY through a service call) | decision 7i | yes |

## 4. SCH: V-file schema runner (MIGRATION level, PostgreSQL + H2)

`SchemaMigrator` applies `V{n}__*.sql` by `ResourceDatabasePopulator` and records `schema_history(version, file_name, sha256, applied_at, outcome)`. Only `ledger-cli migrate` runs it, with the owner account; the apps never run DDL.

| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| SCH-001 | MIGRATION | empty database | `migrate` | all V files applied in numeric order (V2 before V10); one `schema_history` row per file, `outcome=SUCCESS`, `file_name`, `sha256` equal to an independent SHA-256, `applied_at` UTC `Z`; exit 0 | e2e.sh fresh install | yes |
| SCH-002 | MIGRATION | schema already applied | `migrate` again | nothing executed, no new history rows, schema digest unchanged, exit 0 | e2e.sh:S-I01,S-I02 | yes |
| SCH-003 | MIGRATION | applied V2 file edited afterwards | `migrate`, then app start | `migrate` exits non-zero naming the version; the app refuses to start (health DOWN, message names version only); a `FAILED` row is appended for that version (the earlier SUCCESS row stays); data untouched; a digest mismatch of an already SUCCESSFUL version is never retried | user decision 1; decision 7f | yes |
| SCH-004 [PostgreSQL part moved: test server stage, see 1.4a] | MIGRATION | V3 whose 3rd statement is invalid | `migrate` | exit non-zero; `schema_history` has V3 `FAILED`, V1..V2 `SUCCESS`; V4 not applied; app refuses to start. PostgreSQL leaves no partial V3 objects (transactional DDL); H2 may keep earlier V3 statements (implicit DDL commit): the test records which and asserts only the history/outcome contract on both | e2e.sh:S-U03 (rewritten) | yes |
| SCH-005 | MIGRATION | after SCH-004 (V3 latest outcome FAILED) | `migrate` again without fixing, then after fixing the V3 file | each attempt retries V3 and appends one `schema_history` row (second FAILED row, later SUCCESS row); V4 is not applied until V3's latest outcome is SUCCESS; after the fix V3 and V4 apply and the final schema digest equals a clean run | e2e.sh:S-U03; decision 7f | yes |
| SCH-020 | MIGRATION | V3 attempted 3 times (FAILED, FAILED, SUCCESS) | read `schema_history` | 3 rows for version 3 in attempt order (`applied_at` ascending, UTC), outcomes FAILED, FAILED, SUCCESS; `file_name` and `sha256` per attempt are those of the file at that attempt; no row is updated or deleted | decision 7f | yes |
| SCH-021 | MIGRATION | the app and `migrate` decide "up to date" | version with rows SUCCESS then a later FAILED row appended by a digest-mismatch attempt | latest outcome FAILED -> not up to date, app refuses to start; the guard uses the latest outcome per version, not any SUCCESS row | decision 7f | yes |
| SCH-006 | MIGRATION | files V1, V3 (no V2); or new V2 added after V3 was applied | `migrate` | refused (gap / out-of-order) (P) | design | yes |
| SCH-007 | MIGRATION | files `v1__x.sql`, `V1_x.sql`, `V01__x.sql`, `V1__x.txt` | scan | non-matching names rejected with a message, not silently skipped (P) | design | no |
| SCH-008 | MIGRATION | V file with `--` comments, blank lines, a string literal containing `;` | apply | statements split correctly or the file is rejected by the ANSI check (no silent mis-split); no procedure bodies exist | design | yes |
| SCH-009 | MIGRATION | two `migrate` processes at once on an empty DB | run concurrently | final schema complete; exactly one SUCCESS row per version; the loser waits or exits cleanly (non-zero with a clear message), never a half-applied file | e2e.sh:S-U04,S-U05 (concurrent upgrade) rewritten | yes |
| SCH-010 | MIGRATION | pending (unapplied) V file or FAILED row | start mcp-server and admin-web | startup fails fast; `/healthz` never reports UP; no writes happen on the old schema | e2e.sh:S-U04 (writers during upgrade) rewritten | yes |
| SCH-011 | MIGRATION | CRLF and LF checkouts of the same files | compute digest / apply | no false digest mismatch (P: digest over LF-normalised content, or `.gitattributes eol=lf` enforced and tested) | design | no |
| SCH-012 | MIGRATION | `schema_history` | inspect | columns exactly version, file_name, sha256, applied_at, outcome; outcome CHECK IN ('SUCCESS','FAILED'); sha256 64 hex | user decision 5 | yes |
| SCH-013 [moved: test server stage, see 1.4a] | MIGRATION | PostgreSQL and H2 databases built from the same files | compare logical schema | same table, column, PK, UNIQUE, FK name sets and nullability | plan: DBMS neutral | yes |
| SCH-014 | MIGRATION | owner vs DML account | run `migrate` as DML account | refused by the database (no DDL right); app account can `SELECT` `schema_history` | plan: owner/DML split | yes |
| SCH-015 | MIGRATION | database with the *legacy* `ledger` schema | `migrate` | creates only the new schema objects; legacy objects untouched (digest of legacy tables unchanged) | plan: old schema kept | yes |
| SCH-016 | MIGRATION | seeds in V files | after fresh `migrate` | common codes: severities P1,P2,P3 ACTIVE and UNRATED INACTIVE; relation kinds same_site, caused_by, blocks, same_family, same_fix_unit, regression_of ACTIVE; category group empty; exactly the three groups exist | e2e.mjs:K1; e2e.sh:S-F02 | yes |
| SCH-018 | MIGRATION | schema from V files | list every timestamp-typed column through `DatabaseMetaData.getColumns` (TIMESTAMP type codes) | the set of `table.column` equals exactly: `defect.created_at, defect.updated_at, task.created_at, task.updated_at, defect_fix.ci_completed_at, defect_fix.recorded_at, defect_case.observed_at, defect_case.recorded_at, session_grant.granted_at, session_grant.expires_at, session_grant.revoked_at, session_grant.last_used_at, history.at, request_receipt.created_at, schema_history.applied_at` (any addition or removal fails the test until the list and the converter wiring are reviewed); no such column has a default; none is of a zone-bearing type | user decision: UTC columns | yes |
| SCH-019 | MIGRATION | the 15 columns of SCH-018 | write each through its repository with JVM zone `Asia/Seoul` and DB zone `Asia/Seoul` (guard bypassed) while counting calls of the single UTC JDBC converter | every one of the 15 writes and reads passes through that one converter (call count equals number of timestamp parameters), raw stored value equals the UTC wall time; the same test finds no timestamp parameter bound by another path (statement spy: only converter-produced values) | user decision: single UTC converter | yes |
| SCH-017 | MIGRATION | seed counters | after `migrate` | every project counter 0 (none yet), `id_counter` rows initialised, `code_revision` row exists | plan | yes |

## 5. DRV: JDBC driver registration (no bundled driver)

Driver registration is stored outside the database (the DB cannot be opened before a driver exists) in `/etc/<name>/jdbc-driver.yaml` (mode 0640, readable by the service user), read through `spring.config.import`. Registration is performed through the CLI command `driver-register` and the admin web driver page (setup mode included); the effective driver changes only after restart.

| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| DRV-001 | CLI | readable test-driver jar (H2) and class `org.h2.Driver`, JDBC URL for H2 | `driver-register --jar <path> --class <name>` | accepted; stored record has absolute path, class, SHA-256 of the jar (equals independent digest), registered_at UTC `Z`; output shows path, class, digest (never DB password) | new: user decision 2 | no |
| DRV-002 | CLI | path does not exist / is a directory / unreadable / not absolute / contains `..` | register | `DRIVER_INVALID` (P) naming the reason; nothing stored | new | no |
| DRV-003 | CLI | file that is not a ZIP (text file renamed `.jar`), empty file, truncated jar | register | `DRIVER_INVALID`; nothing stored | new | no |
| DRV-004 | CLI | valid jar, class name absent from the jar; class exists but does not implement `java.sql.Driver`; class with no public no-arg constructor | register | `DRIVER_INVALID` each, message distinguishes the three; nothing stored | new | no |
| DRV-005 | CLI | driver class loads but `acceptsURL(configured JDBC URL)` is false (H2 driver with a PostgreSQL URL) | register / validate | `DRIVER_INVALID` "driver does not accept URL"; URL value in the message is sanitised (no password) | new | no |
| DRV-006 | SERVICE | registered H2 jar | build the DataSource | chain is `URLClassLoader` -> `Driver` instance -> `SimpleDriverDataSource` -> Hikari; a connection is obtained and a query runs | new | yes |
| DRV-007 | SERVICE | after startup | inspect `DriverManager.getDrivers()` and the call graph | the registered driver is not registered in `DriverManager` (list empty or unchanged); no `DriverManager.getConnection` anywhere (see ARC-027) | new | yes |
| DRV-008 | SERVICE | registered jar changed on disk after registration (different SHA-256) | start the app | startup fails with a message naming the path and "digest mismatch"; no connection attempted | new (P: integrity check at start) | no |
| DRV-009 | SERVICE | registered jar deleted before start | start MCP server / CLI command / admin | MCP server and CLI fail fast with the path in the message; the admin starts in setup mode (ADM-042) | new | no |
| DRV-010 | SERVICE | running app, new driver registered | check running pool | running pool unchanged; after restart the new driver is used (proved with a distinguishing driver build) | new: "applied after restart" | no |
| DRV-011 [PostgreSQL part moved: test server stage, see 1.4a] | SERVICE | PostgreSQL driver registered then H2 driver registered | restart each time | each start uses the registered driver; both pass SVC smoke (`save` + `ledger_get`) | new | yes |
| DRV-012 | SERVICE | no registration at all (no `jdbc-driver.yaml`) | start mcp-server; run each CLI command except `driver-register` | every one fails fast with a clear reason naming `driver-register` (`DRIVER_NOT_CONFIGURED`), exit non-zero, no vendor stack trace, no connection attempt; `driver-register` itself works | new; decision 7g | no |
| DRV-013 | ARCH | built artifacts (`bootJar` of mcp-server, admin-web, cli) | scan every nested jar and class | zero `META-INF/services/java.sql.Driver`, zero classes implementing `java.sql.Driver`, no `org.postgresql`, `org.h2` or any other driver package (H2/PG only in test classpath) | new | no |
| DRV-014 | ARCH | Gradle dependency graph | resolve `runtimeClasspath` of all apps | contains no JDBC driver artifact | new | no |
| DRV-015 | ADMIN | admin session (also in setup mode) | submit driver registration form with the DRV-002..005 invalid inputs | each rejected with `DRIVER_INVALID` ProblemDetail/validation message, CSRF required, nothing stored | new | no |
| DRV-016 | ADMIN | admin session, valid jar | register | stored with SHA-256, page shows "takes effect after restart"; page lists path, class, digest | new | no |
| DRV-017 | CLI | registration of a jar that is group/world-writable | register | refused or warned (P: code is loaded into the process) | new (security) | no |
| DRV-020 | CLI | `driver-register` run as root for service user `ledger` | register | `/etc/<name>/jdbc-driver.yaml` exists with mode 0640, group = service group, content has jar path, class, SHA-256, registered_at UTC `Z`; written atomically (temp file + move, never wider than 0640); second registration replaces it | decision 7g | no |
| DRV-021 | SERVICE | yaml file present | start mcp-server, admin, CLI | all three read it through `spring.config.import` (no custom parser); a malformed or incomplete yaml fails fast naming the missing key; secrets (DB password) are not stored in this file | decision 7g | no |
| DRV-022 | ADMIN | admin in setup mode | open any page other than login and driver registration, e.g. `/defects`, `/api/overview`, `/grants` | refused (503 `DRIVER_NOT_CONFIGURED` or redirect to the driver page), nothing is read from the database; login works with the admin credential; after a successful registration the page says "restart required" | decision 7g | no |
| DRV-023 | CLI | `driver-register` with the DRV-002..005 invalid inputs | register | no file written/changed (an existing valid yaml stays byte-identical) | decision 7g | no |
| DRV-018 | SERVICE | two drivers in one jar (H2 + extras) and `class` selects one | register | exactly the named class is instantiated; others never loaded | new | no |
| DRV-019 | SERVICE | class loader lifecycle | context closes | the `URLClassLoader` is closed, no file handle on the jar remains (jar can be deleted on Windows hosts) | new | no |

## 6. SVC: application services (SERVICE level)

Default DB H2; rows marked `yes` also run on embedded PostgreSQL. Fixtures: project `e2e`/`E2E`; actors `claude:e2e-author` (grants defect, task, fix, verify on the project), `codex:e2e-reviewer` (fix, verify), `gemini:e2e-nogrant` (none). Channel AUTHOR = write tools, REVIEWER = `ledger_review`, `ledger_review_correct`. Time comes from an injected fixed UTC `Clock`.

### 6.1 SVC-GATE: write gate (`WriteGate.open(actor, project, scope, channel)`)
Order asserted by SVC-GATE-17: verify-needs-reviewer, attestation present, format, project known, grant, then reason, then receipt, then business rules.

| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| SVC-GATE-01 | SERVICE | actor null | any write service (save, transition, record_fix, record_case, link, correct, batch, review, review_correct) and whoami | `HOST_ATTESTATION_REQUIRED`, nothing written, nothing in `request_receipt` | e2e.mjs:H2 | no |
| SVC-GATE-02 | SERVICE | actor `claude` / `user:someone` / `` (empty) | save | `AUTHOR_ID_FORMAT` (also when the actor would have no grant) | e2e.mjs:E-04,E-05 | no |
| SVC-GATE-03 | SERVICE | actor `gemini:e2e-nogrant` | save defect | `SESSION_NOT_GRANTED`, details author, scope `defect`, project | e2e.mjs:E-03 | no |
| SVC-GATE-04 | SERVICE | AUTHOR channel, actor holds `verify` grant | transition to VERIFIED / MET / NOT_MET, or out of VERIFIED | `VERIFY_NEEDS_REVIEWER_LOGIN`; same through batch op `transition` | e2e.sh:S-D01; e2e.mjs:E-26 | no |
| SVC-GATE-05 | SERVICE | REVIEWER channel, actor with `defect` grant only | transition OPEN -> DISPUTED via review | allowed (reviewer channel may do non-verify transitions), history `login`=`ledger_reviewer`; with verify transition and no `verify` grant `SESSION_NOT_GRANTED` | sql grants (`transition` granted to both logins) | no |
| SVC-GATE-06 | SERVICE | grant matrix | for each operation call with only one scope granted | save defect needs `defect`; save task/condition needs `task`; transition defect `defect`, task/condition `task`; any verify transition `verify`; record_fix `fix`; record_case `defect`; link defect_task and relation kinds `defect`; link condition_task/condition_defect `task`; correct by corrected entity (defect, defect_task, defect_relation, defect_case `defect`; defect_fix `fix`; task, condition, condition_link `task`; a verify transition row `verify`); wrong scope -> `SESSION_NOT_GRANTED` | sql each function | no |
| SVC-GATE-07 | SERVICE | grant scope matrix per project | grant on project P1 only, write on P2 | `SESSION_NOT_GRANTED` naming P2 | sql `_begin` | no |
| SVC-GATE-08 | SERVICE | task or condition moved to another project | save with `project_id` change | requires grants on both source and target project, else `SESSION_NOT_GRANTED` | sql save | no |
| SVC-GATE-09 | SERVICE | relation link between defects of two projects | link | requires `defect` grant on both projects | sql link | no |
| SVC-GATE-10 | SERVICE | expired grant (clock past `expires_at`), expiry equal to now, never-expiring grant | write | expired and equal-to-now refused; never-expiring accepted | sql `_begin`; REPO-015 | no |
| SVC-GATE-11 | SERVICE | revoked grant; then a new grant for the same key | write | refused while revoked; accepted after re-grant | sql `grant_session` | no |
| SVC-GATE-12 | SERVICE | grant revoked after a committed write | replay of that write with the same request id | `SESSION_NOT_GRANTED` (the gate runs before the receipt lookup, a revoked session cannot read stored responses) | sql order `_begin` before `_reserve` | no |
| SVC-GATE-13 | SERVICE | live grant | successful write | `last_used_at` set to the clock value; failed write does not touch it (rolled back with the tx) | plan: conditional UPDATE | no |
| SVC-GATE-14 | SERVICE | author ids `claude:ABC` and `claude:abc` | grant for one, write as other | refused: author id comparison is case-sensitive | 10_tables | no |
| SVC-GATE-15 | SERVICE | project cannot be determined (save without `project_id`, unknown id) | save | `VALIDATION_FAILED` (project_id required) / `NOT_FOUND` | sql save | no |
| SVC-GATE-16 | SERVICE | `user:owner` actor | write | format accepted; still needs a grant like any actor | sql `_author_ok` | no |
| SVC-GATE-17 | SERVICE | pairs of simultaneous failures | each pair: (no attestation + bad project) (bad format + no grant) (no grant + reason too short) (reason too short + stale version) (stale version replay of a stored receipt) | the earlier gate's error wins in every pair; replay with stored receipt returns the stored response even if the version has moved | sql order | no |
| SVC-GATE-18 | SERVICE | `ScopedValue` actor | 20 parallel calls with different actors | each result `author` equals its own actor; no leakage between calls; actor unbound after the call | plan: ScopedValue | no |
| SVC-GATE-19 | SERVICE | grant check and write share a transaction | exception after the gate | rollback restores `last_used_at` | plan | no |

### 6.2 SVC-RCP: receipts (idempotency)
| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| SVC-RCP-01 | SERVICE | save with request id R | repeat identical call | returns the stored response (equal, same `history_id`); no new rows in history/entity; receipt count 1 | e2e.mjs:E-07,E-08 | no |
| SVC-RCP-02 | SERVICE | R used by save | reuse R with different reason / id / expected_version / fields | `REQUEST_CONFLICT`, nothing written | e2e.mjs:E-09; C7 | no |
| SVC-RCP-03 | SERVICE | R used by save | reuse R in transition (other operation) | `REQUEST_CONFLICT` | sql `_reserve` | no |
| SVC-RCP-04 | SERVICE | R used by actor A through AUTHOR | same payload by actor B; same payload, A, REVIEWER channel | `REQUEST_CONFLICT` both | sql `_reserve` author/login | no |
| SVC-RCP-05 | SERVICE | R stored | same payload with key order changed, `fields` as JSON string vs object, extra whitespace, `author_id` present/absent | replay (equal normalised digest) | DOM-028 | no |
| SVC-RCP-06 | SERVICE | first call with R fails (VERSION_CONFLICT, NOT_FOUND, grant error) | retry with R and a corrected payload | succeeds and writes once; no `REQUEST_CONFLICT` (receipt rolled back with the failed tx) | plan: receipt in same tx | no |
| SVC-RCP-07 | SERVICE | stored receipts for transition, record_case on a defect merged afterwards, record_fix, link | replay after the world changed | stored response returned (replay precedes version, merged and state checks) | e2e.mjs:C9 | no |
| SVC-RCP-08 | SERVICE | request id omitted | two identical calls | two receipts, two writes (documented behaviour of a blind retry) | dbhub.toml comment | no |
| SVC-RCP-09 | SERVICE | 16 threads, same R, same payload | all call at once | exactly one commit, 1 history row, 1 receipt; all 16 callers receive an equal response; none sees an error | plan: concurrent duplicates | yes |
| SVC-RCP-10 | SERVICE | 16 threads, same R, 4 different payloads | all call | exactly one payload wins; every other payload gets `REQUEST_CONFLICT`; same-payload callers get the stored response | plan | yes |
| SVC-RCP-11 | SERVICE | after SVC-RCP-09/10 stress and 200 mixed writes | scan receipts | no committed receipt with null response; receipt `payload_sha256` 64 hex; `operation`, `author_id`, `login`, `created_at` UTC set | sql `_finish` | yes |
| SVC-RCP-12 | SERVICE | non-UUID request id | call | `VALIDATION_FAILED` | DOM-007 | no |
| SVC-RCP-13 | SERVICE | app restart between call and replay | replay | stored response returned | plan | no |
| SVC-RCP-15 | SERVICE | request id L of an imported (LEGACY) receipt | replay any write with L (same payload as the legacy one, a different payload, a payload that hashes the same by accident) | `REQUEST_ID_LEGACY` every time; the stored response is never returned; nothing written; a brand-new id works | decision 7i | no |
| SVC-RCP-14 | SERVICE | batch with request id B | replay B | whole stored `results`; also per-op derived ids make the ops idempotent individually | e2e.mjs:E-22 | no |

### 6.3 SVC-IDS: server-assigned ids
| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| SVC-IDS-01 | SERVICE | project id `e2e`, code `E2E`, no defects | create defect without id twice | `E2E-D-0001`, `E2E-D-0002`; version 1; status OPEN (project code, not project id, forms the prefix) | e2e.mjs:E-07,E-10 | no |
| SVC-IDS-02 | SERVICE | explicit defect id `OTHER-D-0009` | create | `ID_PREFIX_MISMATCH` | e2e.mjs:E-11 | no |
| SVC-IDS-03 | SERVICE | explicit `E2E-D-0100`, counter at 3 | create, then create without id | first accepted and raises the counter; next auto id is `E2E-D-0101` (never a duplicate) | new: plan id allocation | yes |
| SVC-IDS-04 | SERVICE | explicit id of an existing defect with version 0 | create | `DUPLICATE_KEY` | new | no |
| SVC-IDS-05 | SERVICE | failures (validation error, no grant, reason error) | then a good create | failed requests consume no number; next id contiguous | new | yes |
| SVC-IDS-06 | SERVICE | task create without id; with explicit `E2E-T-1`, `E2E-T\|PIPE` | save | `E2E-T-0001` issued; explicit free-text ids accepted | plan: task ids; e2e.mjs:P2 | no |
| SVC-IDS-07 | SERVICE | explicit `E2E-T-0002` exists, counter at 1 | create without id | issued id is not a duplicate (counter raised or number skipped) | new | yes |
| SVC-IDS-08 | SERVICE | two projects | create tasks/defects in both | independent sequences | plan | no |
| SVC-IDS-09 | SERVICE | condition create without id | save | `VALIDATION_FAILED` (conditions keep explicit ids; no server numbering in the plan) | sql save `id required` | no |
| SVC-IDS-10 | SERVICE | 50 parallel creates of defects, 50 of tasks (same project) | run | 50 distinct sequential ids each, no gaps | plan: concurrent id allocation | yes |
| SVC-IDS-11 | SERVICE | counter at 9999 | create | `E2E-D-10000` | DOM-005 | no |
| SVC-IDS-12 | SERVICE | batch with two creates without ids then a failing op | run | consecutive ids on success; on failure the counter rolls back (next create reuses) | new | yes |
| SVC-IDS-13 | SERVICE | history, fix, case, grant allocation | 64 concurrent writes | ids distinct, no gaps (REPO-013 at service level) | user decision 1 | yes |

### 6.4 SVC-NORM: empty strings
| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| SVC-NORM-01 | SERVICE | optional text fields `evidence`, `user_quote`, `branch`, `ci_ref`, `origin_ref`, `origin_quote`, `goal_label`, `owner_actor_id`, `merged_into`, `evidence_uri`, `evidence_sha256`, grant `note` given as `""` or `"   "` | write | stored as NULL (never `''`); a blank `ci_ref` with `ci_conclusion` -> `VALIDATION_FAILED` (conclusion needs a reference) | user decision 6; 10_tables ci_ref CHECK | no |
| SVC-NORM-02 | SERVICE | required fields `title`, `observation`, `reason`, `repo`, `condition_text`, `release`, `code` blank | write | `VALIDATION_FAILED` / `REASON_INVALID` with path; `purpose` / `success_condition` (NOT NULL columns) blank -> `VALIDATION_FAILED`: a NOT NULL column never holds `''` (old data allowed it; legacy rows are handled by MIG-023) | user decision 6; decision 7j | no |
| SVC-NORM-03 | SERVICE | `evidence_uri` `""` on VERIFIED | review | `VALIDATION_FAILED` / `EVIDENCE_REQUIRED` (old code accepted `''` as evidence; now it equals absent) | sql transition | no |
| SVC-NORM-04 | SERVICE | `""` vs absent in the same payload | two calls with the same request id | replay (normalised before hashing) | DOM-028 | no |
| SVC-NORM-05 | SERVICE | edit that sets an optional field to `""` when it holds a value | save | field becomes NULL and the history `changes` records `[old,null]` | new | no |

### 6.5 SVC-SAV: save
| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| SVC-SAV-01 | SERVICE | valid defect fields | create | result keys `status=COMMITTED, entity, id, version=1, history_id, author, login`; history CREATE row, `changes` `{field:[null,value]}` for every given field (including `project_id`), `before_data` NULL | e2e.mjs:E-07; sql save | no |
| SVC-SAV-02 | SERVICE | defect without `severity` / without `category` | create | `CODE_REQUIRED` with `details.valid` = `P1,P2,P3` (category: active category codes); old text `CODE_REQUIRED: defect.severity is required, one of: P1, P2, P3` is carried as the message | e2e.mjs:K3 | no |
| SVC-SAV-03 | SERVICE | severity `UNRATED` on a new defect | create | `CODE_INVALID`, valid list `P1,P2,P3` | e2e.mjs:K4 | no |
| SVC-SAV-04 | SERVICE | category `BGU`; inactive category `RETIRED` | create / edit that changes category | `CODE_INVALID`; edit of an unrelated field on a row already holding `RETIRED` is allowed | e2e.mjs:K5; e2e.sh:S-C05 | no |
| SVC-SAV-05 | SERVICE | defect at version 1 | edit title with expected_version 1 | version 2, `updated_at` = clock, history EDIT with `changes {title:[old,new]}` only for changed fields | e2e.mjs:E-13 | no |
| SVC-SAV-06 | SERVICE | defect at version 2 | edit with expected_version 7 | `VERSION_CONFLICT`, message expected 7 current 2 | e2e.mjs:E-12 | no |
| SVC-SAV-07 | SERVICE | edit without id; edit of unknown id; id of another entity | save | `VALIDATION_FAILED` / `NOT_FOUND` / `NOT_FOUND` | sql save | no |
| SVC-SAV-08 | SERVICE | edit with identical values | save | `NOTHING_CHANGES`, no history | sql save | no |
| SVC-SAV-09 | SERVICE | defect edit with `project_id` | save | `PROJECT_MOVE_FORBIDDEN` | e2e.mjs:E-14 | no |
| SVC-SAV-10 | SERVICE | fields with `status`, `version`, `created_by`, `id`, `merged_into`, `state`, `updated_at`, or a misspelling | save | `VALIDATION_FAILED` naming the path; `created_by` can never be forged | e2e.mjs:P1 | no |
| SVC-SAV-11 | SERVICE | `owner_actor_id` bad format / valid | save | `AUTHOR_ID_FORMAT` / accepted | sql save | no |
| SVC-SAV-12 | SERVICE | task create | save | state PLANNED, version 1, owner/origin fields stored; edit updates `updated_at` | sql save | no |
| SVC-SAV-13 | SERVICE | condition create | save | state UNKNOWN, version 1, no `updated_at` column; edit bumps version only | sql save | no |
| SVC-SAV-14 | SERVICE | second condition with same (project, release, code) | save | `DUPLICATE_KEY` | 10_tables condition UNIQUE | no |
| SVC-SAV-15 | SERVICE | unknown project on create | save | `NOT_FOUND` | sql save | no |
| SVC-SAV-16 | SERVICE | reason 9 / 1001 chars | save | `REASON_INVALID`, no receipt row | sql `_check_reason` | no |
| SVC-SAV-17 | SERVICE | field length limits (DOM-029) over the service | save | rejected with `VALIDATION_FAILED` before any SQL write (assert statement spy sees no INSERT) | 10_tables | no |
| SVC-SAV-18 | SERVICE | `expected_version` 0 with `id` of another existing entity kind | create | no collision across entity kinds (separate tables) | 10_tables | no |
| SVC-SAV-19 | SERVICE | edit by actor B of a row created by A | save | `created_by` stays A, history author B | e2e.mjs:P5 | no |

### 6.6 SVC-TRN: transitions and review
| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| SVC-TRN-01 | SERVICE | unknown entity; unknown state `FIXED`; unknown link kind | transition / link | `VALIDATION_FAILED` listing allowed values | e2e.mjs:K6,K7 | no |
| SVC-TRN-02 | SERVICE | defect without fix | to SOURCE_FIXED | `FIX_REQUIRED` | e2e.mjs:E-15 | no |
| SVC-TRN-03 | SERVICE | stale expected version | transition | `VERSION_CONFLICT`; replay with receipt precedes the check | sql transition | no |
| SVC-TRN-04 | SERVICE | defect already in `to` state | transition | `TRANSITION_INVALID` | sql transition | no |
| SVC-TRN-05 | SERVICE | service called directly (tool validation bypassed) with VERIFIED, no evidence; VERIFIED and no fix | review | `EVIDENCE_REQUIRED` / `FIX_REQUIRED` (state guard is part of the service, not only of the tool schema) | e2e.sh:S-D02,S-D03 (rewritten) | no |
| SVC-TRN-06 | SERVICE | defect fixed by session A (only fix) | review VERIFIED by A | `SAME_SESSION` | e2e.mjs:E-17; V3 | no |
| SVC-TRN-07 | SERVICE | NOGRANT session | review VERIFIED | `SESSION_NOT_GRANTED` | e2e.mjs:E-18 | no |
| SVC-TRN-08 | SERVICE | SOURCE_FIXED defect, reviewer with verify grant, evidence | review VERIFIED | result keys `from,to,version(+1),history_id,author,login=ledger_reviewer`; history TRANSITION with from/to state, `evidence_uri`, `request_id` | e2e.mjs:V1-verify,E-30 | no |
| SVC-TRN-09 | SERVICE | VERIFIED defect | review to OPEN without evidence / with evidence | refused / allowed; verify scope; AUTHOR channel `VERIFY_NEEDS_REVIEWER_LOGIN` | sql transition | no |
| SVC-TRN-10 | SERVICE | NOT_DEFECT / DISPUTED without evidence | transition | `EVIDENCE_REQUIRED` | sql transition | no |
| SVC-TRN-11 | SERVICE | MERGED with live target; self; merged target; unknown | transition | allowed with `merged_into` stored and history `changes {merged_into:[null,target]}`; others `MERGE_TARGET_INVALID`/`NOT_FOUND` | e2e.mjs:C8 | no |
| SVC-TRN-12 | SERVICE | merged defect | transition MERGED -> OPEN | `merged_into` cleared | sql transition | no |
| SVC-TRN-13 | SERVICE | task flow: author reports done with evidence; author verifies; reviewer verifies; reviewer sends back; original reporter verifies; same reviewer re-verifies | six calls | steps 1 ok (version 2); 2 `SAME_SESSION`; 3 ok (3); 4 ok (4); 5 `SAME_SESSION`; 6 ok (5) | e2e.mjs:V7 | no |
| SVC-TRN-14 | SERVICE | task owner equals verifier | review | `SAME_SESSION` | sql transition | no |
| SVC-TRN-15 | SERVICE | task to UNKNOWN; REPORTED_DONE without evidence | transition | `TRANSITION_INVALID` / `EVIDENCE_REQUIRED` | sql transition | no |
| SVC-TRN-16 | SERVICE | condition | to MET without evidence; to MET by reviewer with evidence | `EVIDENCE_REQUIRED`; ok with scope verify and REVIEWER channel | sql transition | no |
| SVC-TRN-17 | SERVICE | transition result | any transition | keys `status, entity, id, from, to, version, history_id, author, login` | sql transition | no |
| SVC-TRN-18 | SERVICE | `evidence_sha256` malformed | transition | `VALIDATION_FAILED` | 10_tables | no |

### 6.7 SVC-FIX: record_fix
| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| SVC-FIX-01 | SERVICE | OPEN defect, no CI | record_fix | defect becomes SOURCE_FIXED (version +1), keys `defect, fix_id, defect_status, owner_actor_id=author, version, history_id, transition_history_id, author, login` | e2e.mjs:V1-fix | no |
| SVC-FIX-02 | SERVICE | `ci_conclusion` `passed` | record_fix | `VALIDATION_FAILED` (allowed success, failure, cancelled) | e2e.mjs:K8 | no |
| SVC-FIX-03 | SERVICE | conclusion without `ci_ref`; blank `ci_ref` | record_fix | `VALIDATION_FAILED` | 10_tables defect_fix CHECKs | no |
| SVC-FIX-04 | SERVICE | commit sha of 39, 41 chars, uppercase hex | record_fix | `VALIDATION_FAILED` | 10_tables | no |
| SVC-FIX-05 | SERVICE | SOURCE_FIXED defect, other session records a new commit | record_fix | owner becomes that session (EDIT history `owner_actor_id [old,new]`), version +1 (e.g. 3), status unchanged | e2e.mjs:V5 | no |
| SVC-FIX-06 | SERVICE | same commit again without CI | record_fix | `FIX_ALREADY_RECORDED` | sql record_fix | no |
| SVC-FIX-07 | SERVICE | same commit with changed CI | record_fix | defect_fix EDIT history with `changes`, owner/status unchanged | sql record_fix | no |
| SVC-FIX-08 | SERVICE | same commit with identical CI | record_fix | `NOTHING_CHANGES` | sql record_fix | no |
| SVC-FIX-09 | SERVICE | `mark_source_fixed=false` on OPEN defect | record_fix | stays OPEN; owner set with EDIT history; version +1 | sql record_fix | no |
| SVC-FIX-10 | SERVICE | CI failure / cancelled / mixed (first by author, second by other session without CI) | record_fix then review by independent session | all verifiable (CI is optional metadata); `ci_conclusion` retained; own fix cannot be verified by the author (`SAME_SESSION`) even when CI success | e2e.mjs:V2,V3,V5 | no |
| SVC-FIX-11 | SERVICE | verified rows without CI | attention query | `VERIFIED_WITHOUT_CI` never appears | e2e.mjs:V1-att | no |
| SVC-FIX-12 | SERVICE | defect created by A, fixed by A, second fix by B, verified by A | read defect | `created_by`=A, `owner_actor_id`=B, `fixed_by`=B, `last_author`=A | e2e.mjs:V6,P5 | no |
| SVC-FIX-13 | SERVICE | record_fix | read history | `entity=defect_fix`, `entity_id=<defect>@<commit>`, `evidence_uri`=ci_ref; fix `recorded_by` = author (P4) | e2e.mjs:P4 | no |
| SVC-FIX-14 | SERVICE | defect unknown; actor lacks `fix` | record_fix | `NOT_FOUND` / `SESSION_NOT_GRANTED` | sql record_fix | no |
| SVC-FIX-15 | SERVICE | `ci_completed_at` with `+09:00`, with `Z`, without zone | record_fix | the first two store the same UTC instant; naive rejected | user decision 4 | no |
| SVC-FIX-16 | SERVICE | fix on a MERGED defect | record_fix | allowed as the old function allowed it (unchanged by design) | sql record_fix | no |

### 6.8 SVC-CAS: observed cases
| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| SVC-CAS-01 | SERVICE | live defect D1 | record_case with evidence, user quote, `observed_at` | keys `entity=defect_case, id=<D1>\|<case_id>, case_id, defect, history_id, author, login`; case row keeps all fields | e2e.mjs:C1 | no |
| SVC-CAS-02 | SERVICE | case recorded | history | one CREATE row `entity=defect_case`, author, login `ledger_author`; `before_data` NULL | e2e.mjs:C2 | no |
| SVC-CAS-03 | SERVICE | observation `"   "` | record_case | `VALIDATION_FAILED` (path observation); no row | e2e.mjs:C3 | no |
| SVC-CAS-04 | SERVICE | ungranted actor | record_case | `SESSION_NOT_GRANTED` | e2e.mjs:C4 | no |
| SVC-CAS-05 | SERVICE | unknown defect | record_case | `NOT_FOUND` | e2e.mjs:C5 | no |
| SVC-CAS-06 | SERVICE | same request id and payload | record_case twice | replay, same `history_id`, still 1 case | e2e.mjs:C6 | no |
| SVC-CAS-07 | SERVICE | same request id, other observation | record_case | `REQUEST_CONFLICT` | e2e.mjs:C7 | no |
| SVC-CAS-08 | SERVICE | defect D4 live, case recorded, D4 merged into D3 | record_case on D4 | `MERGED_DEFECT`, message `D4 was merged into D3` (target named) | e2e.mjs:C8 | no |
| SVC-CAS-09 | SERVICE | retry (same request id) of the case recorded before the merge | record_case | replay returns the stored response | e2e.mjs:C9 | no |
| SVC-CAS-10 | SERVICE | batch with two `record_case` ops on D3 | batch | count 2, both `defect_case` on D3 | e2e.mjs:C10 | no |
| SVC-CAS-11 | SERVICE | batch with a case on D3 then on merged D4 | batch | `MERGED_DEFECT` with `details.op_index=2`, no case persisted | e2e.mjs:C11 | no |
| SVC-CAS-12 | SERVICE | D3 with cases, D4 merged into D3 | read defect cases | D3 cases + D4 cases + D4 behaviour as `merged_behavior`, ordered `coalesce(observed_at, at)`, `case_id` nulls first, defect | e2e.mjs:C12 | no |
| SVC-CAS-13 | SERVICE | D3 merged into D2 (chain) | read D2 cases | cases of D2, D3, D4 and `merged_behavior` of D3 and D4; terminates for chain depth 50 | e2e.mjs:C13; REPO-036 | no |
| SVC-CAS-14 | SERVICE | case with evidence, quote, observed_at | read | all kept, `observed_at` equals the instant, `by` = recorder | e2e.mjs:C14 | no |
| SVC-CAS-15 | SERVICE | correct on a case history row | correct | `corrects` = that history id, row appended | e2e.mjs:C15 | no |
| SVC-CAS-16 | SERVICE | limits observation 4000/4001, evidence 2000/2001, quote 2000/2001; `observed_at` absent / naive | record_case | boundary per DOM-029; absent allowed; naive rejected | 15_defect_case | no |

### 6.9 SVC-LNK: links
| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| SVC-LNK-01 | SERVICE | defects D1, D2 | link `same_family` (D2, D1) then (D1, D2) | id `D1\|D2\|same_family`; second is `NOTHING_CHANGES`; history LINK `changes {kind}` | e2e.mjs:E-19 | no |
| SVC-LNK-02 | SERVICE | defect and task | link `defect_task` | id `defect\|task`; scope defect | sql link | no |
| SVC-LNK-03 | SERVICE | condition, task, defect | link `condition_task`, `condition_defect` | id `cond\|target`; scope task on the condition's project | sql link | no |
| SVC-LNK-04 | SERVICE | unknown kind `related` | link | `VALIDATION_FAILED` with allowed kinds (3 fixed + active relation codes) | e2e.mjs:K7; e2e.sh:S-C06 | no |
| SVC-LNK-05 | SERVICE | inactive relation kind | link | `CODE_INVALID` | sql `_check_codes` | no |
| SVC-LNK-06 | SERVICE | existing link | remove (`remove=true`) | UNLINK history, row gone; removing an absent link `NOTHING_CHANGES` | sql link | no |
| SVC-LNK-07 | SERVICE | unknown defect / condition / task | link | `NOT_FOUND` | sql link | no |
| SVC-LNK-08 | SERVICE | `a == b` relation | link | `VALIDATION_FAILED` | 10_tables CHECK a<b | no |
| SVC-LNK-09 | SERVICE | fresh seed | link `regression_of` | accepted | e2e.sh:S-C07 | no |
| SVC-LNK-10 | SERVICE | `code-set RELATION_KIND test_relation` just added | link `test_relation` without restart | accepted | e2e.mjs:K2 | no |
| SVC-LNK-11 | SERVICE | links with ids containing `\|` (`E2E-T\|PIPE`) | link and unlink | stored with unambiguous keys; no collision with a differently split pair | e2e.sh:S-U20 | no |

### 6.10 SVC-COR: corrections
| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| SVC-COR-01 | SERVICE | history row H of defect create | correct(H) | keys `status, corrects=H, history_id, author, login`; CORRECT row has `corrects_id=H`, `before_data` NULL; H itself unchanged (digest) | e2e.mjs:E-20 | no |
| SVC-COR-02 | SERVICE | unknown history id | correct | `NOT_FOUND` | sql correct | no |
| SVC-COR-03 | SERVICE | corrected entity types (defect, defect_fix, defect_case, defect_task, defect_relation, task, condition, condition_link) | correct | project resolved from the entity id (`defect\|case`, `defect@commit`, `cond\|target`); scope per SVC-GATE-06 | sql correct | no |
| SVC-COR-04 | SERVICE | history row of a verify transition | correct via AUTHOR channel / via `ledger_review_correct` | `VERIFY_NEEDS_REVIEWER_LOGIN` / allowed with `verify` grant | sql correct | no |
| SVC-COR-05 | SERVICE | CORRECT row corrected again; replay; short reason | correct | allowed (unchanged by design); replay stored; `REASON_INVALID` | sql correct | no |

### 6.11 SVC-BAT: batch
| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| SVC-BAT-01 | SERVICE | save, save, link in one batch | batch | `count=3`, results ids `D-0003`, `D-0004`, link entity `defect_relation`; later op sees earlier op writes | e2e.mjs:E-21 | no |
| SVC-BAT-02 | SERVICE | same batch request id replayed | batch | identical stored `results` | e2e.mjs:E-22 | no |
| SVC-BAT-03 | SERVICE | op1 save ok, op2 transition with expected_version 9 | batch | error `VERSION_CONFLICT` with `details {op_index:2, op:"transition"}` and message ending "nothing was written"; no defect, no history, no receipt, counter rolled back | e2e.mjs:E-23,E-24 | yes |
| SVC-BAT-04 | SERVICE | NOGRANT actor | batch | `SESSION_NOT_GRANTED` | e2e.mjs:E-25 | no |
| SVC-BAT-05 | SERVICE | `transition` to VERIFIED inside batch (AUTHOR channel) | batch | `VERIFY_NEEDS_REVIEWER_LOGIN` | e2e.mjs:E-26 | no |
| SVC-BAT-06 | SERVICE | op with unknown key `titel`; transition without `expected_version`; op missing `reason`; `op` unknown | batch | `VALIDATION_FAILED` with path `ops[i].<key>`; all-or-nothing | e2e.mjs:E-27,E-28 | no |
| SVC-BAT-07 | SERVICE | 0, 1, 200, 201 ops; ops not an array | batch | 0 and 201 and non-array rejected; 1 and 200 accepted | sql batch | no |
| SVC-BAT-08 | SERVICE | op with its own `request_id`; batch replay | batch | explicit id used for that op; others derived | sql batch | no |
| SVC-BAT-09 | SERVICE | 8-op batch: task, condition, three links, second task, second condition, link | batch | `count=8` | e2e.mjs:P2 | no |
| SVC-BAT-10 | SERVICE | batch containing record_fix, correct, record_case | batch | each op uses the same gates as its single tool (grants per op) | sql batch | no |
| SVC-BAT-11 | SERVICE | batch id with different ops than first time | batch | `REQUEST_CONFLICT` | sql batch | no |
| SVC-BAT-12 | SERVICE | two ops edit the same row | batch | second op's `before_data` is the intermediate state; version +2 | user decision 3 | no |

### 6.12 SVC-PRV: provenance
| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| SVC-PRV-01 | SERVICE | batch creating task, condition, defect links by AUTHOR | read `created_by` of task, defect, condition, defect_task, defect_relation, condition_link | all equal AUTHOR (first host author) | e2e.mjs:P2,P3 | no |
| SVC-PRV-02 | SERVICE | admin/CLI project-add and code-set | read `created_by` | `user:owner` (service binds actor for owner operations) | e2e.sh:S-C03 | no |
| SVC-PRV-03 | SERVICE | edit by another actor | read | `created_by` unchanged | e2e.mjs:P5 | no |

### 6.13 SVC-BEF: `history.before_data`
`before_data` = JSON of the full entity record as it was immediately before the change, written in the same transaction as the `changes` diff. Field names equal column names (P); instants end in `Z`.
| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| SVC-BEF-01 | SERVICE | create defect, task, condition, case, link, new fix commit | read history rows | `before_data` NULL for every CREATE/LINK | user decision 3 | no |
| SVC-BEF-02 | SERVICE | defect edit | read the EDIT row | `before_data` equals a snapshot read just before the call (all columns incl. `version`=expected, old `updated_at`, `created_by`); `changes` diff still present; current row differs | new | yes |
| SVC-BEF-03 | SERVICE | task and condition edits | read | same for each entity (condition without `updated_at`) | new | no |
| SVC-BEF-04 | SERVICE | defect/task/condition transitions through transition and review | read | `before_data` has the previous state and version; leaving MERGED shows the old `merged_into` | new | no |
| SVC-BEF-05 | SERVICE | record_fix: (a) new commit on OPEN defect (b) new commit by another session on SOURCE_FIXED (c) CI update of an existing commit (d) `mark_source_fixed=false` | read history | (a) fix CREATE: NULL; defect TRANSITION: defect before_data (OPEN, old owner); (b) defect EDIT: before_data of defect; (c) defect_fix EDIT: before_data = old fix row; (d) defect EDIT with before_data | new | no |
| SVC-BEF-06 | SERVICE | link then unlink | read | LINK NULL; UNLINK `before_data` = the removed link row (P: removal is a modification of existing state) | new | no |
| SVC-BEF-07 | SERVICE | record_case; correct | read | NULL (they only insert history/case rows); the `case_seq` bump on the defect row is a lock device, not a modification: no defect history row, no `before_data`, `version` and `updated_at` unchanged | new; decision 7m | no |
| SVC-BEF-08 | SERVICE | any op fails after `before_data` was prepared (injected exception after the row update) | call | zero history rows, zero receipts, entity unchanged, counters unchanged | user decision 3 | yes |
| SVC-BEF-09 | SERVICE | concurrent edits with the same expected version | two threads | winner's `before_data.version` = expected; loser `VERSION_CONFLICT`, no history | user decision 3 | yes |
| SVC-BEF-10 | SERVICE | replay of an EDIT | replay | no second history row, no second `before_data` | new | no |
| SVC-BEF-11 | SERVICE | defect with 4000-char Korean behaviour | edit | `before_data` stores it exactly | REPO-019 | yes |
| SVC-BEF-12 | SERVICE | default `ledger_history`/`ledger_defect` reads | read | no `before_data` unless `include_before=true` (see MCR-H11) | new | no |

### 6.14 SVC-CC: common codes
| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| SVC-CC-01 | SERVICE | group `X` / the three valid groups | set code | `VALIDATION_FAILED` for `X`; only DEFECT_CATEGORY, DEFECT_SEVERITY, RELATION_KIND accepted | admin INVALID_CODE_GROUP; e2e.mjs:K1 | no |
| SVC-CC-02 | SERVICE | new code `UI_UX` with ko/en text, sort 25 | set | stored ACTIVE; `code_revision` +1; usable in save immediately (no restart) | admin-ui:A9 | no |
| SVC-CC-03 | SERVICE | active code | set with state INACTIVE | row stays (never deleted), no longer valid for new writes; existing rows keep it | admin-ui:A9; e2e.sh:S-C05 | no |
| SVC-CC-04 | SERVICE | existing code | set with different key / delete | no delete or rename path; same key updates text/state only; `COMMON_CODE_DELETE_FORBIDDEN` at the admin boundary | sql `_code_keep` (rewritten) | no |
| SVC-CC-05 | SERVICE | text limits | name ko/en 0, 1, 200, 201; description 0, 500, 501 (admin limit); code `^[A-Za-z][A-Za-z0-9_]{0,31}$` | `VALIDATION_FAILED` out of range | admin INVALID_CODE* | no |
| SVC-CC-06 | SERVICE | English text omitted | set | falls back to the Korean text | ledger-admin code-set | no |
| SVC-CC-07 | SERVICE | identical re-set (no value change) | set | no revision bump (P: avoids spurious `list_changed`) | new | no |
| SVC-CC-08 | SERVICE | set rolled back | set + exception | revision unchanged | plan | no |
| SVC-CC-09 | SERVICE | code lists | read | ordered by `sort_order`, `code`; seed per SCH-016 | sql | no |
| SVC-CC-10 | SERVICE | category group empty | save defect | `CODE_REQUIRED` with empty valid list and guidance to add a category | e2e.sh:S-F01 | no |

### 6.15 SVC-GRT: grants and projects
| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| SVC-GRT-01 | SERVICE | grant author, project, scopes defect+task, expiry, note | grant | one row per scope; earlier open grant for the same key closed (`revoked_at`), rows never deleted; `granted_by` `user:owner` | sql `grant_session` | no |
| SVC-GRT-02 | SERVICE | validations | grant | author format `AUTHOR_ID_FORMAT`; empty scope list, unknown scope, project unknown `VALIDATION_FAILED`/`NOT_FOUND`; note 501 chars; expiry not after now (`expires_at > granted_at`) | admin POST /api/grants | no |
| SVC-GRT-03 | SERVICE | live grants | revoke by author / author+project / author+project+scope | revokes exactly the matching live grants and returns the count; scope without project -> `VALIDATION_FAILED` | admin-ui:A8 | no |
| SVC-GRT-04 | SERVICE | whoami / grants list | read | live only (not revoked, not expired), ordered by project, scope, keys `project, scope, expires_at` | sql whoami | no |
| SVC-GRT-05 | SERVICE | project add | `id ^[A-Za-z0-9_-]{1,64}$`, code per DOM-004, title 1..200; duplicates | invalid -> `VALIDATION_FAILED`; duplicate id or code -> `DUPLICATE_KEY`; counters start 0; row stamped `user:owner` | admin POST /api/projects | no |
| SVC-GRT-06 | SERVICE | two parallel grants for the same key | grant | exactly one live grant remains | REPO-020 | yes |

### 6.16 SVC-TZ: UTC
| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| SVC-TZ-01 [PostgreSQL part moved: test server stage, see 1.4a] | SERVICE | DB session zone Asia/Seoul (PG `SET TIME ZONE`, H2 `SET TIME ZONE`) | start app / run startup guard comparing `LOCALTIMESTAMP` with the UTC `Clock` | startup refuses, message names "database session time zone is not UTC" (no connection string); with UTC it starts | user decision: DB UTC | yes |
| SVC-TZ-02 | SERVICE | JVM default zone Asia/Seoul and DB zone Asia/Seoul (guard bypassed by a test switch) | write then read an `Instant` | stored value is UTC (REPO-029), service output equals the input instant | user decision | yes |
| SVC-TZ-03 | SERVICE | fixed UTC `Clock` at `2026-11-05T14:59:59Z`, `15:00:00Z` (= midnight KST) and `2026-11-05T23:59:59Z`/`00:00:00Z` | grant expiry, `created_at` stamping, search date range, cursor, stats bucketing | every result computed from UTC instants only; at 14:59:59Z -> 15:00:00Z nothing changes day (KST boundary has no effect); at 23:59:59Z -> 00:00:00Z the UTC day changes | user decision | no |
| SVC-TZ-04 | SERVICE | input timestamps `2026-11-05T10:00:00Z`, `2026-11-05T19:00:00+09:00`, `2026-11-05T10:00:00`, `2026-11-05` for `observed_at`, `ci_completed_at`, grant `expires_at`, search range | submit | first two same Instant; last two rejected | DOM-034 | no |
| SVC-TZ-05 | SERVICE | any stored or returned instant | format | ends in `Z`; no zone conversion call exists in main code (ARC-024) | user decision | no |

### 6.17 SVC-RACE: races
| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| SVC-RACE-01 | SERVICE | latch-driven interleavings: merge of D5 into D2 starts, record_case on D5 starts (a) while merge waits at its end (b) after merge commit | run both | after (a) and (b): the merge commits; the case is either refused with `MERGED_DEFECT: <D5> was merged into <D2>` or recorded before the merge and then visible as a case of D2; invariant: no case on a MERGED defect has a history id greater than that defect's merge TRANSITION row; no case remains that is invisible to the target. Mechanism (decision 7m): `record_case` runs `UPDATE defect SET case_seq = case_seq + 1 WHERE id = ? AND status <> 'MERGED'`; affected rows 0 -> `MERGED_DEFECT`; the merge updates the same row so the two serialise; `case_seq` does not bump `version` | e2e.sh:S-R01,S-R02,S-R03 | yes |
| SVC-RACE-09 | SERVICE | defect D1 with `version` 3 | 20 `record_case` calls, sequentially and then 20 in parallel | `case_seq` +20, 20 cases, `version` still 3, no defect history row, a concurrent `ledger_save` with `expected_version` 3 still succeeds (the bump does not cause `VERSION_CONFLICT`) | decision 7m | yes |
| SVC-RACE-02 | SERVICE | two transitions with the same expected version | run concurrently | exactly one commits, the other `VERSION_CONFLICT` (never a raw SQL error) | sql 40001 | yes |
| SVC-RACE-03 | SERVICE | two edits with the same expected version | run | same | sql save | yes |
| SVC-RACE-04 | SERVICE | two record_fix on one OPEN defect, different commits | run | defect SOURCE_FIXED, exactly one OPEN->SOURCE_FIXED TRANSITION history row, every successful call has its fix row; losers may get `VERSION_CONFLICT`, nothing else | sql record_fix "no write skew" | yes |
| SVC-RACE-05 | SERVICE | two reviewers verify the same SOURCE_FIXED defect | run | exactly one VERIFIED; the other `VERSION_CONFLICT` or `TRANSITION_INVALID` | new | yes |
| SVC-RACE-06 | SERVICE | two identical links | run | one OK, one `NOTHING_CHANGES`; no duplicate-key leak | sql link | yes |
| SVC-RACE-07 | SERVICE | write while the grant is revoked in parallel | run 200 rounds | never a write committed after the revoke commit; REPO-016 at service level | sql FOR SHARE (rewritten) | yes |
| SVC-RACE-08 | SERVICE | code-set vs save using the new code | run | save uses either old or new code set consistently, no exception other than `CODE_INVALID` | new | yes |

## 7. MCW: MCP write-tool contract (MCP Java SDK client -> Boot server, H2)

Ten write tools: `ledger_save, ledger_transition, ledger_record_fix, ledger_record_case, ledger_link, ledger_correct, ledger_batch, ledger_review, ledger_review_correct, ledger_whoami`. Tool names, input keys, `_hostAttestation` and error tokens are the external contract.

| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| MCW-001 | MCP | started server | `tools/list` | contains all ten write tools; contains no tool named `execute_sql*` or `search_objects*` (writing or reading) | e2e.mjs:E-01,E-02 | no |
| MCW-002 | MCP | each write tool schema | inspect | `_hostAttestation` is an optional string (not in `required`); `author_id` is not a declared property; unknown properties are not allowed | e2e.mjs:H1 | no |
| MCW-003 | MCP | each of the ten tools | call without `_hostAttestation` | `HOST_ATTESTATION_REQUIRED` for save, whoami and the other eight (transition, record_fix, record_case, link, correct, batch, review, review_correct); nothing written | e2e.mjs:H2 (both blocks) | no |
| MCW-004 | MCP | whoami with `_hostAttestation=AUTHOR` and `author_id=NOGRANT` | call | `author` is AUTHOR and `grants` has the 4 AUTHOR grants: legacy `author_id` accepted and ignored | e2e.mjs:H3 | no |
| MCW-005 | MCP | save with only legacy `author_id=AUTHOR` | call | `HOST_ATTESTATION_REQUIRED` | e2e.mjs:H3 | no |
| MCW-006 | MCP | arguments `titel`, `authorId`, `Author_Id`, `_hostattestation`, extra nested key in `fields` | call | `VALIDATION_FAILED` naming each unknown argument; `author_id` is the only accepted legacy key; `_hostAttestation=""` -> `AUTHOR_ID_FORMAT` | plan: unknown argument rejected | no |
| MCW-007 | MCP | `fields` as JSON object and as legacy JSON string with the same content | call both with the same `request_id` | identical effect; second is a receipt replay (same normalised digest) | plan: object and legacy string | no |
| MCW-008 | MCP | `ops` as JSON array and as legacy JSON string | `ledger_batch` | identical results | plan | no |
| MCW-009 | MCP | `fields` as invalid JSON string, `"[]"`, `"7"`, `"null"`, `null`, `{}`; `ops` as `"{"`, `{}`, `[]` | call | `VALIDATION_FAILED`, nothing written | plan; sql save/batch | no |
| MCW-010 | MCP | whoami with granted attestation | call | keys `session_user` (login label of the channel, P), `author`, `author_format_ok=true`, `can_verify_login=false`, `grants[]{project,scope,expires_at}` | e2e.mjs:E-06 | no |
| MCW-011 | MCP | whoami with ungranted / malformed attestation | call | ungranted: empty `grants`; malformed (`claude`): `author_format_ok=false` | sql whoami | no |
| MCW-012 | MCP | all results of write tools | inspect response | `structuredContent` is the result record itself (keys as in SVC tables); no `rows[0].result` wrapper; text content carries the same JSON; `isError=false` | plan: remove DBHub wrapper; e2e.mjs `result()` helper | no |
| MCW-013 | MCP | a failing call | inspect | `isError=true`, `structuredContent.code` = token, `details`, text content begins `<TOKEN>: `; message localised by `MessageSource`; no SQL, class names or stack traces | plan: error conversion | no |
| MCW-014 | MCP | `ledger_transition` input schema | inspect `to` | enum of author-channel states only (no VERIFIED, MET, NOT_MET); `ledger_review` `to` includes them; sending VERIFIED to `ledger_transition` -> `VALIDATION_FAILED` | e2e.mjs:E-16; K6 | no |
| MCW-015 | MCP | `ledger_review` without `evidence_uri` | call | `VALIDATION_FAILED` path `evidence_uri` (structural, Jakarta group is the source of truth) | e2e.mjs:V3 | no |
| MCW-016 | MCP | enum schemas | inspect | `entity` = defect/task/condition; `ci_conclusion` = success/failure/cancelled; `ledger_link.kind` = `defect_task, condition_task, condition_defect` + active RELATION_KIND codes in `sort_order` | e2e.mjs:K2,K6,K8 | no |
| MCW-017 | MCP | `ledger_review` description | read | states CI is optional metadata, the reviewer runs the real program directly, and points to the `fixed_by` field of `ledger_defect` | e2e.mjs:V1 (rewritten: `v_defect.fixed_by`) | no |
| MCW-018 | MCP | `expected_version` as `"3"`, 3.5, -1; `request_id` not UUID; `reason` of 9 chars | call | `VALIDATION_FAILED` / `REASON_INVALID` (no numeric-string coercion) | plan: structural validation | no |
| MCW-019 | MCP | AUTHOR-channel tools vs REVIEWER-channel tools | call verify transition through `ledger_transition` with `_hostAttestation` of a verifier | `VERIFY_NEEDS_REVIEWER_LOGIN` (or schema rejection); through `ledger_review` ok; `ledger_correct` of a verify row refused, `ledger_review_correct` allowed | e2e.sh:S-D01 | no |
| MCW-020 | MCP | injected failure inside a tool call after the first insert | call | whole call rolled back (proxy-applied `@Transactional` and validation active regardless of the callback path) | plan: tool through service proxy (spike c) | no |
| MCW-021 | MCP | 20 parallel sessions with different attestations | call whoami/save | each result's `author` equals the caller's attestation | plan: ScopedValue | no |
| MCW-022 | MCP | body above the configured limit | call | HTTP 413 / `PAYLOAD_TOO_LARGE`, nothing written, server keeps serving | plan (resource limit) | no |
| MCW-023 | MCP | `ledger_save` input schema | inspect `fields` | typed properties per the create/edit groups; `category` and `severity` carry the active codes as enum + description (P path `fields.category.enum`) | e2e.mjs:K1 | no |
| MCW-024 | MCP | tools invoked as `ledger_get`-style read tool with `_hostAttestation` | call | read tools do not need it; property is not declared (see MCR-001) | plan | no |
| MCW-025 | MCP | the MCP scenario of the old suite, executed in order with the same data (E-07..E-28, C1..C15, P1..P5, V1..V7 through the tools) | run | every step has the outcome listed in the SVC cases it maps to | e2e.mjs whole file | no |

## 8. MCR: MCP read-tool contract (12 tools)

Tools: `ledger_get, ledger_defect, ledger_task, ledger_history, ledger_search, ledger_defects, ledger_tasks, ledger_conditions, ledger_attention, ledger_stats, ledger_projects, ledger_codes`. Input/output keys other than `max_rows`, `cursor`, `truncated`, `next_cursor`, `include_before` are provisional `(P)`; each case's semantic is binding. List tools return `items[]`, `truncated`, `next_cursor`.

### 8.1 Common behaviour
| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| MCR-001 | MCP | the 12 read tools | inspect schemas | each declares `outputSchema`; `structuredContent` validates against it; none declares `_hostAttestation` or `author_id`; unknown args rejected | plan: `generateOutputSchema` | no |
| MCR-002 | MCP | list tools (`ledger_history, ledger_search, ledger_defects, ledger_tasks, ledger_conditions, ledger_attention, ledger_projects, ledger_codes`) | `max_rows` = 1 and 1000 | accepted | plan | no |
| MCR-003 | MCP | same | `max_rows` = 0, -1, 1001, "a", 50.5 | `VALIDATION_FAILED` path `max_rows` | plan | no |
| MCR-004 | MCP | 120 rows | no `max_rows` | 50 items, `truncated=true`, `next_cursor` present | plan: default 50 | no |
| MCR-005 | MCP | exactly `max_rows` rows exist | list | `truncated=false`, `next_cursor` absent/null | plan: setMaxRows(n+1) | no |
| MCR-006 | MCP | `max_rows+1` rows exist | list | `truncated=true`, `next_cursor` present, `items.length = max_rows` | plan | no |
| MCR-007 | MCP | each list tool, 137 rows, `max_rows` 10 | follow `next_cursor` until absent | every row exactly once, stable order, last page `truncated=false` | plan: cursor | no |
| MCR-008 | MCP | page 1 fetched, then 5 new rows inserted and 5 existing rows edited | fetch page 2.. | no duplicate, no skipped original row (cursor stability); new rows appear only where the sort order puts them (newest-first lists: not in later pages; history by id ascending: at the end) | plan: cursor stability | no |
| MCR-009 | MCP | garbage, truncated, other tool's cursor, cursor with changed filters | call | `VALIDATION_FAILED` (P: cursor bound to tool + filters) | DOM-031 | no |
| MCR-010 | MCP | any read call | compare row counts, versions, `updated_at` before/after 100 calls | unchanged (`@Transactional(readOnly=true)`); no attestation required; no grant needed | e2e.mjs:E-31,E-32 (rewritten) | no |
| MCR-011 | MCP | server | call `execute_sql_reader`, `search_objects_reader`, `ledger_codes` | the first two: unknown-tool protocol error with no side effect; `ledger_codes` exists (the old "removed" check is inverted, see section 18) | e2e.mjs:K1,E-31 | no |
| MCR-012 | MCP | timestamps in any output | inspect | every instant ends with `Z` | user decision 4 | no |
| MCR-013 | MCP | no bearer / wrong bearer | any read tool | HTTP 401 | plan | no |

### 8.2 ledger_get
| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| MCR-G01 | MCP | one defect, one task, one condition id | `ledger_get(ids=[d,t,c])` | three items in request order, each with `entity`, `id`, lifecycle state (`state`; a defect's status mapped, filters accept `status` or `state`, P), `version`, `project_id` | plan | no |
| MCR-G02 | MCP | unknown id among known ones; all unknown; duplicate ids | call | unknown ids listed in `not_found` (P), not an error; duplicates returned once | plan | no |
| MCR-G03 | MCP | version read via get | `ledger_save` with that `expected_version` | succeeds (the read-then-write loop) | plan | no |
| MCR-G04 | MCP | an id that is both a defect id and a task id (old alias case) | call | both items returned, distinguished by `entity` | e2e.sh:S-U18 | no |
| MCR-G05 | MCP | `ids` empty or above the cap (P: 200) | call | `VALIDATION_FAILED` | plan | no |
| MCR-G06 | MCP | merged defect | call | item shows `merged_into` | plan | no |
| MCR-G07 | MCP | verified defect + history | get then history | state VERIFIED, project, version as in the old "reader sees the verified defect" check | e2e.mjs:E-29 | no |

### 8.3 ledger_defect / ledger_task
| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| MCR-D01 | MCP | defect with fixes, tasks, conditions, cases, relations | `ledger_defect` | fields of old `v_defect`: id, project_id, title, category, severity, status, merged_into, behavior, root_cause, owner_actor_id (= owner, else latest fixer), version, updated_at, created_by, fixed_by, fixed_at, last change (at, author, login, assurance, op, reason), `fixes[]{commit,repo,branch,ci,ci_conclusion,by}`, `tasks[]{task,state,owner}`, `conditions[]`, `cases[]`, relations (P), recent changes (P) | sql v_defect | no |
| MCR-D02 | MCP | merge chain D4->D3->D2 | `ledger_defect(D2)` | cases of D2, D3, D4 with `source` `case`/`merged_behavior`, order `coalesce(observed_at,at)`, `case_id` nulls first, defect; depth 50 terminates | e2e.mjs:C12,C13,C14 | no |
| MCR-D03 | MCP | defect with IMPORT history rows only after real changes | `ledger_defect` | last-change fields ignore IMPORT rows | sql v_defect | no |
| MCR-D04 | MCP | defect created by A, fixed by B | `ledger_defect` | `created_by`=A, `owner_actor_id`=`fixed_by`=B, `fixed_at` set | e2e.mjs:P5,V6 | no |
| MCR-D05 | MCP | unknown id | call | `NOT_FOUND` | sql | no |
| MCR-T01 | MCP | task with owner, reported done by A, verified by B, sent back | `ledger_task` | title, purpose, success_condition, origin_ref, origin_quote, owner, state, version, `completed_by` = original reporter A (P key), linked defects, conditions | plan | no |
| MCR-T02 | MCP | unknown task id | call | `NOT_FOUND` | plan | no |

### 8.4 ledger_history
| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| MCR-H01 | MCP | 20 history rows | `ids=[3,7,9]` | exactly those rows ascending | plan | no |
| MCR-H02 | MCP | same | `id_from=5,id_to=9` | rows 5..9 inclusive | plan | no |
| MCR-H03 | MCP | defect with 4 history rows | `entity=defect, entity_id=D1` | the 4 rows, ascending by id; states `OPEN->SOURCE_FIXED` at index 3, last author = reviewer, login `ledger_reviewer` | e2e.mjs:E-30 | no |
| MCR-H04 | MCP | two projects | `project` filter; unknown project | only that project's rows; unknown project returns empty `items` with success | admin-ui:A7 | no |
| MCR-H05 | MCP | mixed ops and authors | `op=TRANSITION`, `author_id=...` | filtered; combined filters are ANDed | plan | no |
| MCR-H06 | MCP | rows around midnight UTC/KST | `at_from`, `at_to` | inclusive UTC range | plan; SVC-TZ-03 | no |
| MCR-H07 | MCP | any filter | order | always ascending by id; cursor is the last id | plan | no |
| MCR-H08 | MCP | EDIT row | read | `changes` is a structured object of `[old,new]` pairs, not a string; CORRECT rows show `corrects_id`; `request_id`, `legacy_ref`, `vendor` (derived from author, `agent:` prefix ignored) present | sql v_history | no |
| MCR-H09 | MCP | receipt check | write, then history by the returned `history_id` | row with the same `request_id` and author | plan: history = receipt | no |
| MCR-H10 | MCP | imported legacy rows | read | IMPORT/LEGACY_NOTE/LEGACY_CHANGE rows visible with `legacy_ref`, `actor_assurance` | 10_tables | no |
| MCR-H11 | MCP | EDIT, TRANSITION, UNLINK rows with `before_data` | `include_before=true` / `false` / absent | true: `before_data` returned as structured object equal to the stored record; false or absent: key omitted; CREATE, LINK, CORRECT, case rows and imported rows: null even with true | user decision 3 | no |
| MCR-H12 | MCP | `include_before=true`, `max_rows=1000` | call | response is complete or truncated by `max_rows` rules (no silent cut), within configured response size | user decision 3 | no |
| MCR-H13 | MCP | a defect created and then edited through the write tools (MCP Java SDK client, real server) | `ledger_history(project, include_before=true)` | the EDIT row's `before_data` is an object whose keys are snake_case like every other key of the contract (`project_id`, `root_cause`, `case_seq`, `created_at`, `created_by`, ...) and holds the row as it was before the edit (title of the CREATE, version 1); the CREATE row's `before_data` is null. A camelCase key (`projectId`) must not appear, whichever mapper writes the response | defect found in the MCP stage: the snapshot records were written with the default naming | no |

### 8.5 ledger_search
| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| MCR-S01 | MCP | rows containing `Login` only in title / purpose / behavior / root_cause / condition_text | keyword `login` and `LOGIN` | each of the five fields matches case-insensitively and the owning row (defect, task or condition) is returned | plan | no |
| MCR-S02 | MCP | rows A (`alpha beta`), B (`alpha`), C (`beta`), D (`gamma`) | keywords `[alpha, beta]` with `match` absent, `ANY`, `ALL`, `any`, `AND` | absent and `ANY`: A, B, C; `ALL`: A only; `any` (wrong case) and `AND` -> `VALIDATION_FAILED` listing `ANY, ALL` (P key name `match`); one keyword gives the same under both | decision 7h | no |
| MCR-S03 | MCP | rows created on 2026-11-04T23:59:59Z, 05T00:00:00Z, 05T14:59:59Z, 05T15:00:00Z | `created_from`/`created_to` | inclusive UTC bounds exactly as instants | plan; SVC-TZ-03 | no |
| MCR-S04 | MCP | defects, tasks, conditions | `kind` filter, `project`, `exclude_status=[MERGED,NOT_DEFECT]` | only matching kinds/projects; excluded states absent | plan | no |
| MCR-S05 | MCP | many matches | search | newest first (created desc, then id desc), paged by cursor | plan | no |
| MCR-S06 | MCP | keyword `100%`, `_x`, `a\b`, `'; DROP TABLE project; --`, blank keyword, 1000-char keyword | search | `%`, `_`, `\` literal; injection text is data; blank or over-long keyword `VALIDATION_FAILED` | REPO-027 | no |
| MCR-S07 | MCP | Korean and ASCII text | search with mixed case | ASCII case-insensitive; Korean exact | plan | no |

### 8.6 Lists, attention, stats, projects, codes
| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| MCR-L01 | MCP | defects in two projects | `ledger_defects(project)` | only that project; project required (P) | plan | no |
| MCR-L02 | MCP | varied defects | filters `status`, `severity`, `category`, `attention` kind, `owner`, and combinations | AND semantics; `owner` matches `owner_actor_id` (fixer fallback); unknown status `VALIDATION_FAILED` with allowed list; severity `UNRATED` accepted as a filter so legacy rows can be found | plan | no |
| MCR-L03 | MCP | tasks / conditions | `ledger_tasks(state, owner)`, `ledger_conditions(state, release)` | filtered, ordered newest first | plan; admin-ui:A7 | no |
| MCR-L04 | MCP | fixtures for the five attention kinds | `ledger_attention` | `OPEN_WITH_FIX`, `FIXED_WITHOUT_COMMIT` (SOURCE_FIXED or VERIFIED without fix), `OPEN_NO_OWNER`, `TASK_UNKNOWN`, `DONE_NOT_VERIFIED` each appear when triggered and disappear when resolved; `VERIFIED_WITHOUT_CI` never | sql v_attention; e2e.mjs:V1-att | no |
| MCR-L05 | MCP | attention across projects | project / kind filter, paging | respected | plan | no |
| MCR-ST01 | MCP | defects, tasks, conditions in all states | `ledger_stats(project)` | counts per status (6, zero-filled), per severity incl. legacy UNRATED, tasks per state (7), conditions per state (3); totals equal row counts; merged counted under MERGED; also without project (all) | plan | no |
| MCR-ST02 | MCP | rows created around midnight UTC/KST | stats with a time bucket (P) | bucketing by UTC day only | SVC-TZ-03 | no |
| MCR-P01 | MCP | projects | `ledger_projects` | id, code, title; paged | plan | no |
| MCR-K01 | MCP | seeded codes + `UI_UX` added + one inactive | `ledger_codes` (group filter, inactive flag) | groups only the three; fields code, name_ko, name_en, description_ko, description_en, sort_order, state; inactive shown only with the flag or flagged (P); reflects a code-set immediately | plan; admin-ui:A7 codes | no |

## 9. MCC: catalogue, `list_changed`, transport, security

| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| MCC-001 | MCP | started server | `tools/list` | exactly 22 tools (10 write + 12 read) with the names above | plan | no |
| MCC-002 | MCP | open session, test configuration registers one more `@McpTool` bean at runtime | observe | client receives `notifications/tools/list_changed`; a new `tools/list` returns 23 and the tool is callable | plan: tool count increases after adding a tool | no |
| MCC-003 | MCP | open session, `code-set DEFECT_CATEGORY UI_UX` done by a different process (admin or CLI) | wait up to 2x the poll interval | `list_changed` received without restarting the server; new `tools/list` shows `UI_UX` in the `ledger_save` schema/description | plan; admin-ui:A9 | no |
| MCC-004 | MCP | code set INACTIVE | same | notification; code removed from enums and description; reactivation shows it again | plan | no |
| MCC-005 | MCP | relation kind added / severity added | same | `ledger_link.kind` enum / severity text updated | e2e.mjs:K2 | no |
| MCC-006 | MCP | no change for three polls | observe | no notification (no spurious `list_changed`); a write tool call does not trigger it | plan | no |
| MCC-007 | MCP | three sessions | code change | all three notified; a fourth session opened afterwards sees the new schema | plan | no |
| MCC-008 | MCP | old session keeps the stale schema | writes with the new code | accepted (service validation reads the DB, not the catalogue) | plan | no |
| MCC-009 | MCP | code change whose transaction rolls back | observe | no notification | plan | no |
| MCC-010 | MCP | DB unavailable for a poll, then back | observe | error logged, no crash, polling resumes, later change still notified | plan (resilience) | no |
| MCC-011 | MCP | `tools/list` called 200 times while the catalogue is rebuilt repeatedly | observe | every response is a full list (old or new), never empty or partial | plan: ToolCatalog | no |
| MCC-012 | MCP | active codes with ko/en text; one with `"quoted"`, newline, backslash, literal `@CODES:DEFECT_CATEGORY@`; inactive `UNRATED` | `tools/list` | each active code listed as `CODE name_ko (name_en): description_ko / description_en` with special characters preserved verbatim (valid JSON, no template substitution); inactive omitted | e2e.mjs:K1 (c,d) | no |
| MCC-013 | MCP | category group empty | `tools/list` | tools still listed; description tells how to add a category (`ledger-cli code-set DEFECT_CATEGORY ...`) (message from the bundle) | e2e.sh:S-F01 | no |
| MCC-014 | MCP | transport | `initialize` over Streamable HTTP at `/mcp` | server capabilities `tools.listChanged=true`; session id header; `DELETE` closes the session | plan | no |
| MCC-015 | MCP | bearer | none / wrong / right | 401 / 401 / 200; comparison is constant time (ARC-020 + unit test with equal-length differing tokens); token never in logs or errors | plan | no |
| MCC-016 | MCP | Host header allowlist | allowed / unlisted / allowed with port / mixed case | passes / refused (`DNS_REBINDING_BLOCKED`-class 403) / passes / passes | plan; e2e.sh:S-W03 | no |
| MCC-017 | E2E | deployed MCP server | connect from a non-loopback address | refused; listens on 127.0.0.1 only | plan | no |
| MCC-018 | MCP | `/healthz` | unauthenticated GET | 200 `{status:UP}`-style, no DB or secret detail; other Actuator endpoints are not exposed (404) | plan | no |
| MCC-019 | MCP | unknown tool, malformed JSON-RPC, oversize frame | call | JSON-RPC error (not a 500); no side effects; server keeps serving | plan | no |
| MCC-020 | MCP | unexpected RuntimeException / DB integrity error in a tool | call | `INTERNAL_ERROR` / `INTEGRITY_VIOLATION`, no SQL text, no vendor class names, logged once | plan: one conversion | no |
| MCC-021 | MCP | Accept-Language ko / en / unsupported | error call | message in that locale; unsupported falls back to the default; token unchanged | plan: MessageSource | no |
| MCC-022 | MCP | Origin header not in allowlist (browser request) | call | refused (P: MCP DNS-rebinding guidance) | plan | no |

## 10. ADM: admin web (Spring MVC + Thymeleaf + Spring Security)

The admin web calls application services directly (no MCP, no `ProcessBuilder`, no root). Credentials in these cases are generated test values.

| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| ADM-001 | ADMIN | running app | `GET /healthz` (public) | 200 body identifies the service; headers `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, CSP containing `default-src 'self'` and `frame-ancestors 'none'` | admin-ui:A1 | no |
| ADM-002 | ADMIN | every response class (page, static asset, 4xx, 5xx, redirect) | inspect headers | CSP and security headers present on all; CSP has no `'unsafe-inline'` for scripts (nonce or external files) | plan: CSP; admin-ui:A1 | no |
| ADM-003 | ADMIN | home and tasks pages | render | skip-link present; tasks page has page-info, page-size select, previous and next controls (previous disabled on the first page, next disabled on the last); CSS has `:focus-visible` | admin-ui:A1 (rewritten from SPA ids) | no |
| ADM-004 | ADMIN | evidence URIs `https://x`, `http://x`, `javascript:alert(1)`, `data:text/html,x`, `file:///etc` | render history/detail | only http(s) become links; the others are plain escaped text | admin-ui:A1 (`isSafeHttpUrl`) | no |
| ADM-005 | ADMIN | reactivating an inactive common code | submit without / with the explicit confirmation field | refused without; accepted with (the Korean confirmation text from the old SPA is a message-bundle string) | admin-ui:A1 (reactivation confirm) | no |
| ADM-006 | ADMIN | text with `<script>`, quotes, `&`, newline in titles, case text, reason | render every page | all escaped by Thymeleaf; no script execution | plan: Thymeleaf | no |
| ADM-007 | ADMIN | unauthenticated browser request to a page; to a JSON endpoint | request | page: redirect to login; endpoint: 401 `UNAUTHORIZED` ProblemDetail | admin-ui:A2 | no |
| ADM-008 | ADMIN | the MCP bearer secret value | use as admin bearer and as login password | 401 `AGENT_TOKEN_FORBIDDEN` both times; no session created | admin-ui:A3; e2e.sh:S-O01 | no |
| ADM-009 | ADMIN | admin token | login (form) | 200/redirect; session cookie `HttpOnly`, `SameSite=Strict`, name from configuration; new session id after login (fixation protection); `GET /api/auth/session`-style check shows authenticated | admin-ui:A6 | no |
| ADM-010 | ADMIN | admin token as `Authorization: Bearer` | call a read endpoint | 200; wrong or empty bearer 401; comparison constant time | admin-ui:A7 | no |
| ADM-011 | ADMIN | logged-in session | logout (POST with CSRF); reuse the old cookie | session invalidated server-side, old cookie 401/redirect, cookie cleared | plan: session | no |
| ADM-012 | ADMIN | session older than the configured lifetime (injected clock) | request | rejected | admin server Max-Age 43200 (config) | no |
| ADM-013 | ADMIN | state-changing POST without CSRF token; with wrong token; with valid token; the old `X-Requested-With` header alone | request | 403 `CSRF_REJECTED` / 403 / success / 403 (the custom header no longer authorises) | admin-ui:A4 (header variant rewritten) | no |
| ADM-014 | ADMIN | valid session cookie, POST from another site (cookie not sent because `SameSite=Strict`; or sent with a missing/foreign CSRF token) | POST | no custom `Sec-Fetch-Site`/`Origin` check exists any more; the cookie carries `SameSite=Strict` (ADM-009) and the Spring CSRF token check rejects the request (ADM-013). dropped: the custom `Sec-Fetch-Site`/`Origin` filters of the old server are replaced by the Spring Security CSRF token and the Strict cookie (decision 7e); the case asserts no such custom filter class exists | admin-ui:A4 (rewritten/dropped part) | no |
| ADM-015 | ADMIN | POST/GET to `/api/save`, `/api/transition`, `/api/correct`, `/api/record_fix`, `/api/review` with a valid session | request | no such handler (404/405); the admin has no ledger-workflow write endpoint | admin-ui:A5 (rewritten) | no |
| ADM-016 | ADMIN | seeded project | open overview, projects, defects, defect detail (with history), tasks (limit 1, offset 0), conditions, history, grants, codes | each 200 and shows the expected rows; tasks page shows total count and paging state; overview shows attention counts | admin-ui:A7 | no |
| ADM-017 | ADMIN | history page for a project that does not exist | open | 200, empty list, total 0 | admin-ui:A7 | no |
| ADM-018 | ADMIN | defect id that does not exist | open detail | 404 `NOT_FOUND` page/ProblemDetail (old `DEFECT_NOT_FOUND`) | admin server | no |
| ADM-019 | ADMIN | project form: id `bad id`, code `abc`, empty title, 201-char title; valid; duplicate | submit | invalid -> `VALIDATION_FAILED` (old `INVALID_PROJECT_ID/CODE/TITLE`); valid created (201/redirect), `created_by=user:owner`; duplicate `DUPLICATE_KEY` | admin server | no |
| ADM-020 | ADMIN | grant form: author `gemini:admin-ui-test`, scopes defect+task, expiry 2099, note | submit | two grants listed; invalid author/scopes/expiry/note/project -> `VALIDATION_FAILED` / `AUTHOR_ID_FORMAT` | admin-ui:A8 | no |
| ADM-021 | ADMIN | the two grants | revoke scope task; revoke the rest; revoke scope without project | listing shows only `defect`; then none; third `VALIDATION_FAILED` (old `PROJECT_REQUIRED_FOR_SCOPE`); missing author (old `AUTHOR_ID_REQUIRED`) | admin-ui:A8 | no |
| ADM-022 | ADMIN | common-code form | delete attempt (`DELETE /codes`, or action delete) | 405 `COMMON_CODE_DELETE_FORBIDDEN` | admin-ui:A9 | no |
| ADM-023 | ADMIN | code form: group `X`, bad code, empty names, 501-char description (ko or en) | submit | `VALIDATION_FAILED` (old `INVALID_CODE_GROUP/CODE/CODE_TEXT/CODE_TEXT_EN`) | admin server | no |
| ADM-024 | ADMIN | code `UI_UX` create, then set INACTIVE | submit both | stored; row still listed as inactive; `code_revision` bumped; no sync/restart step exists (old `sync.steps`=4 dropped) | admin-ui:A9 | no |
| ADM-025 | E2E | admin and MCP processes with an open MCP session | admin code-set | MCP session receives `list_changed` and `UI_UX` appears (cross-process) | admin-ui:A9 (tools/list check); plan | no |
| ADM-026 | ADMIN | request with `X-Forwarded-Proto: https` from a trusted proxy / from an untrusted peer | login | cookie gets `Secure` only when the forwarded header comes from a trusted proxy (configured); otherwise ignored | admin-ui:A10 | no |
| ADM-027 | ADMIN | `Host` allowlist from configuration | request with listed host, listed host with port, unlisted host, empty host, to `/healthz` too | allowed / allowed / 403 `DNS_REBINDING_BLOCKED` / 403 / 403 for unlisted even on `/healthz` | e2e.sh:S-W02,S-W03 | no |
| ADM-028 | ADMIN | 5 wrong credentials from one client within 60 s | 6th attempt (right or wrong) | 429 `TOO_MANY_AUTH_FAILURES` for the lock period; a right credential after the lock window works; success before 5 failures resets the count; counts per client address; failures via expired cookie count too | admin server (MAX 5 / 60 s / 60 s); plan: lockout | no |
| ADM-029 | ADMIN | OIDC not configured | open login, `GET /oauth2/authorization/<id>` | no OIDC option on the page; authorization URL 404 | admin-ui:A12 (rewritten) | no |
| ADM-030 | ADMIN | OIDC client registered against a mock provider (issuer is configuration, no provider literal in code) | start login | 302 to the provider authorization endpoint with `state`, `nonce`, PKCE; callback URI uses the configured external https host | e2e.sh:S-W08 | no |
| ADM-031 | ADMIN | mock provider returns a valid ID token | callback | allowed e-mail -> session; other e-mail, unverified e-mail, wrong audience/issuer/nonce/state, expired token -> 403, no session | plan: OIDC | no |
| ADM-032 | ADMIN | session via OIDC | CSRF, lockout, logout | same rules as token sessions | plan | no |
| ADM-033 | E2E | deployed admin process | inspect | runs as a non-root user (uid != 0); no `runuser`/`systemctl`/`psql` child process during any admin operation | plan: no root | no |
| ADM-034 | ADMIN | oversize body (above limit) | POST | 413 `PAYLOAD_TOO_LARGE` | admin server | no |
| ADM-035 | ADMIN | static request `/..%2f..%2fetc/x`, `//`, `.%2e/` | GET | 400/404, never file content (resources only from the classpath) | admin server `INVALID_PATH` | no |
| ADM-036 | ADMIN | server error | inspect page | no stack trace, no SQL; ProblemDetail with `code` and `traceId` only | plan: `@RestControllerAdvice` | no |
| ADM-037 | ADMIN | admin token / DB password / driver path | grep HTML, headers, logs, error pages | secrets never appear (driver jar path may appear on the driver page only) | plan | no |
| ADM-038 | ADMIN | query string `/?token=<admin token>` | GET | no session is created; token not honoured (dropped: URL tokens leak into logs and history; replaced by the login form; flagged for confirmation) | admin-ui:A11 | no |
| ADM-039 | ADMIN | page with instants | render | every instant is `<time datetime="2026-11-05T10:00:00Z">` (UTC, `Z`) with a UTC text fallback; server never emits a local time or a zone name; response identical under JVM zone `Asia/Seoul` | user decision 4 | no |
| ADM-040 | ADMIN | the bundled display script (static file) | load a page in a real browser (E2E step; NOT_RUN if no browser available on the test host) | `<time>` elements show local time for a browser zone of `Asia/Seoul` and `America/New_York`; the script is an external file (CSP) | user decision 4 | no |
| ADM-041 | ADMIN | driver page | see DRV-015, DRV-016 | rules as in DRV | user decision 2 | no |
| ADM-042 | ADMIN | no `jdbc-driver.yaml` (fresh install) | start admin | admin starts in setup mode: only login and driver registration (and `/healthz`) are exposed; every other route is refused with `DRIVER_NOT_CONFIGURED` (details in DRV-022); the MCP server and CLI keep failing fast (DRV-012) | user decision 2; decision 7g | no |
| ADM-043 | ADMIN | owner operations | create project, code, grant | recorded with actor `user:owner` and appear in the lists | e2e.sh:S-C03 | no |
| ADM-045 | ADMIN | configured MCP health URL answering 200 `UP`, answering 503, refusing the connection, hanging past the client timeout, answering non-JSON | open the overview | status shown UP / DOWN / DOWN / DOWN (timeout reason) / DOWN (invalid answer); the page still renders (no 500); the request is a Spring `RestClient` GET of exactly the configured URL with connect and read timeouts; no bearer or other secret is sent; the response body is not rendered raw | admin-ui:A7 (`mcpHealth.ok`); decision 7d | no |
| ADM-046 | ADMIN | MCP health URL absent from configuration; URL pointing to a non-http scheme or another host than configured | open the overview / change configuration | indicator shows "not configured" and issues no request; only http(s) URLs from configuration are ever requested (no URL taken from a request parameter, no SSRF) | decision 7d | no |
| ADM-044 | ADMIN | pages with more than one page of rows | next/previous | paging stable under inserts (same cursor/offset rules as MCR-008) | admin-ui:A7 | no |

## 11. CLI: `ledger-cli`

Commands (enum, `ApplicationArguments`): `migrate, project-add, code-set, codes, grant, revoke, grants, secret-set, import-legacy, mcp-check, driver-register`. Every command except `driver-register` needs a registered driver (DRV-012). Exit codes: 0 success, 1 failure, 2 usage.
| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| CLI-001 | CLI | no argument / unknown command / missing arguments | run | exit 2, usage lists every command | ledger-admin usage | no |
| CLI-002 | CLI | web-less Boot app | start any command | no server port opened; process exits | plan | no |
| CLI-003 | CLI | empty DB | `migrate` | exit 0, SCH-001 outcome | e2e.sh fresh | yes |
| CLI-004 | CLI | migrated DB | `migrate` again | exit 0, nothing applied, data digest unchanged | e2e.sh:S-I01..S-I03 | yes |
| CLI-005 | CLI | `project-add e2e E2E "End-to-end test project"`; invalid id/code/title; duplicate | run | success prints project; invalid exit 1 `VALIDATION_FAILED`; duplicate `DUPLICATE_KEY` | ledger-admin project-add | no |
| CLI-006 | CLI | `code-set DEFECT_CATEGORY BUG ko-name ko-desc --name-en ... --description-en ... --sort 6 --inactive` | run | creates/updates; inactive flag sets state INACTIVE; special characters (quotes, newline, backslash, literal `@CODES:..@`) preserved; `category-add` is gone (dropped: `code-set DEFECT_CATEGORY ...` covers it, decision 7k) and the command is rejected as unknown (exit 2) | ledger-admin code-set, category-add; e2e.sh owner setup | no |
| CLI-007 | CLI | codes present | `codes [group]` | lists code, names, state, sort, group | ledger-admin codes | no |
| CLI-008 | CLI | `grant claude:e2e-author e2e defect task fix verify --note x --expires <ISO>`; `revoke` variants; `grants [author]` | run | as SVC-GRT-01..04; `--expires` with `+09:00` stored as UTC; naive timestamp rejected | ledger-admin | no |
| CLI-009 | CLI | CLI actions | read `created_by` | `user:owner` | e2e.sh:S-C03 | no |
| CLI-010 | CLI | running servers | `mcp-check --url ... ` | connects with the MCP Java SDK client, lists tools, verifies the 22 expected names, prints a count; exit 0 | mcp-call.mjs `MCP tools OK` | no |
| CLI-011 | CLI | missing tool / wrong bearer / unreachable URL | `mcp-check` | exit 1, message without secret value | mcp-call.mjs | no |
| CLI-012 | CLI | any command | capture output and logs | no secret, no DSN password, no token | plan | no |
| CLI-013 | CLI | `import-legacy` | see MIG area | | plan | yes |
| CLI-014 | CLI | `driver-register` | see DRV-001..005, DRV-017, DRV-020, DRV-023 | | user decision 2; decision 7g | no |
| CLI-015 | CLI | any command except `driver-register` without a registered driver | run | exit 1 `DRIVER_NOT_CONFIGURED` naming `driver-register` | DRV-012 | no |
| CLI-016 | CLI | `--expires` / `observed_at`-like options | see DOM-034 | | user decision 4 | no |

## 12. SEC: secret store (`ledger-cli secret-set`, configtree)

File locations are test directories (configurable); POSIX permission assertions run on Linux hosts, other hosts mark them NOT_RUN honestly.
| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| SEC-001 | CLI | no secrets directory | `secret-set <name>` with hidden input | directory created with mode 0700; file `<name>` created with mode 0600 (via `Files` + `PosixFilePermissions`, mode set at creation, never widened and narrowed later); content equals the input byte for byte | plan: secret store | no |
| SEC-002 | CLI | the same name again | `secret-set` | replaced atomically (temp file 0600 + move); mode stays 0600; no window with wider mode | plan | no |
| SEC-003 | CLI | input source is not a console (piped stdin), or `Console` unavailable | `secret-set` | refuses (exit 1) with a message; never reads the value from arguments, environment or a pipe; the value is not echoed | plan: `Console.readPassword` | no |
| SEC-004 | CLI | commands `secret-get`, `secret-show`, `secret-list`-with-values | run | unknown command (exit 2); no code path prints a stored secret; `secret-set` prints only the name and path | plan: no get | no |
| SEC-005 | CLI | names `../x`, `a/b`, empty, uppercase, 200 chars, `.hidden` | `secret-set` | refused; only `^[a-z][a-z0-9._-]{0,63}$` accepted (P) | plan | no |
| SEC-006 | CLI | existing directory with mode 0755 or file with 0644 | `secret-set` | refuses and names the path/mode (P: fail closed); never silently keeps loose permissions | plan | no |
| SEC-007 | SERVICE | secrets directory with files `ledger.mcp.bearer-token` style names (P) | start app with `spring.config.import=configtree:<dir>` | properties bound into `@ConfigurationProperties` records; missing required secret fails startup naming the property, never a value | plan | no |
| SEC-008 | SERVICE | running app | inspect `/actuator/*`, logs, error responses, thread dumps in logs | the secret value never appears; Actuator `env`/`configprops` not exposed | plan | no |
| SEC-009 | E2E | rotate a secret with `secret-set`, restart | call MCP with old and new bearer | old refused, new accepted | plan | no |
| SEC-010 | E2E | `install` on a host that has the old env keys (`DBHUB_AUTH_TOKEN`, admin token, DB passwords) | run installer | values moved into the secret store (0600), env file keeps only references, old env backed up 0600, nothing printed | plan: env -> secrets | no |
| SEC-011 | E2E | unit file and env file after install | scan | no secret-looking literal (32+ hex/base64 strings, DSN with password) | plan | no |
| SEC-012 | CLI | non-POSIX file system | `secret-set` | fails with an explicit message instead of storing with default permissions | plan | no |
| SEC-013 | CLI | secrets directory or target file owned by another user than the running process (e.g. created by root while the services run as the service account) | `secret-set` | refused before the prompt, exit 1 `FOREIGN_OWNER`, message names path, owner and current user and advises `sudo -u <service account> ledger-cli secret-set <name>`; nothing written | main review of 00f8324: services read the configtree as the service account | no |
| SEC-014 | CLI | parent of the secrets directory (`/etc/<instance>`) missing or not a directory | `secret-set` | refused before the prompt, exit 1 `PARENT_MISSING` naming the parent; only the secrets directory itself is ever created (the installer creates the parent) | main review of 00f8324 | no |
| SEC-015 | SERVICE | a temporary file left in the secrets directory by an interrupted `secret-set` | start an app with `spring.config.import=configtree:<dir>` | the leftover is not bound as a property: temporary names start with `..` (Boot 4.1.2 configtree reads single-dot names but skips `..` names; a characterization test pins this) | main review of 00f8324 | no |

## 13. HK: client hooks (one executable jar, three clients)

Clients: Claude, Codex, Antigravity. One module `ledger-client-hook` builds one executable jar (Jackson inside); the first argument is the client enum `claude|codex|antigravity`; it is launched as `java -jar ledger-client-hook.jar <client>`, reads host JSON on stdin and writes the host's JSON on stdout, stateless. Vendor prefixes: `claude`, `codex`, `gemini`. Client manifests/templates call the same jar with their own argument. (Decision 7m replaces the earlier single-file-source idea; no per-client source files exist.)
| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| HK-001 | HOOK | each client; each of the 10 write tool names in the client's naming (`mcp__<server>__ledger_<op>`; Antigravity `mcp:<server>:ledger_<op>`) with input containing nested data, Korean text, `__proto__` key, `author_id` | run | output adds `_hostAttestation=<vendor>:<host id>` and preserves every other field unchanged (deep equal); Claude: `updatedInput` only and no auto-approval decision; Codex/Antigravity: allow decision, no permission override | claude-hook:CH-01; host-attestation:S01 | no |
| HK-002 | HOOK | absent legacy `author_id` | run | none is created | claude-hook:CH-02; S12 | no |
| HK-003 | HOOK | cached connector schema that requires `author_id` | run | the existing `author_id` is kept, never copied into `_hostAttestation` | S12 | no |
| HK-004 | HOOK | own `_hostAttestation` in the model input with values null, `""`, false, 0, `{}`, `[]`, the expected value, another id | run | explicit deny mentioning "reserved"; no overwrite/updatedInput | CH-03; S02 | no |
| HK-005 | HOOK | host id missing, null, `""`, number, object, 129 chars, whitespace, `other:id`, `bad:id`, `x\n`, `x\r\n`, Korean | run | deny, no fallback to agent/child id | CH-04; S03 | no |
| HK-006 | HOOK | ids `x`, `a._-Z9`, 128 chars | run | accepted and used as is | CH-07; S04 | no |
| HK-007 | HOOK | read tool names (`ledger_get`, `ledger_history`, `ledger_search`, `ledger_defects`...), `ledger_save_extra`, `run_command`, `ledger_save` without MCP prefix, other server tools | run | no output (Claude: `{}`), input untouched; the new read tools are never stamped | CH-05; S05 | no |
| HK-008 | HOOK | malformed JSON; empty stdin; non-object arguments (null, `[]`, text, 7) | run | Claude: exit 2 on malformed JSON; Codex/Antigravity: explicit deny and exit 0; empty stdin no output | CH-06; S06 | no |
| HK-009 | HOOK | invocations with ids A, B, A | run | each uses the supplied id; no state between runs | S07 | no |
| HK-010 | HOOK | client manifests/templates (`hooks.json`, `plugin.json`) | read | matcher regex matches all 10 tool names (all naming variants, `app_ledger_ledger_<op>` namespaces, any server name incl. isolated sink names) and none of the read tools; every command is `java -jar <path>/ledger-client-hook.jar <claude\|codex\|antigravity>` with the right argument and the path exists after install; plugin names | S08; S10; S13 (product part) | no |
| HK-011 | HOOK | Antigravity MCP name candidates `mcp:ledger_save`, `mcp:test:ledger_save`, `mcp:test/ledger_save`, `mcp:test_ledger_save` | run | all stamped | S09 | no |
| HK-012 | HOOK | Antigravity `call_mcp_tool` envelope with `Arguments` as object and as JSON string, extra keys | run | only `Arguments` overwritten, original type kept, envelope untouched; invalid `Arguments` (null, `[]`, 7, `{`, `"text"`) deny; non-ledger ToolName passes | S11 | no |
| HK-013 | HOOK | Codex namespace `mcp__codex_apps__app_ledger_ledger_<op>` | run | all 10 handled; read tool names not | S10 | no |
| HK-014 | HOOK | JSON with numbers `12345678901234567890`, `1e999`, `-0`, `0.10`, escapes, surrogate pairs, deep nesting (100 levels), duplicate keys, 1 MB body | run | Jackson-based round trip: numbers keep their exact value (big integers and decimals are not turned into doubles), strings and unicode preserved, unknown fields passed through untouched; no crash; within the hook time budget | new (Jackson fidelity in the hook jar) | no |
| HK-015 | HOOK | JVM default charset and locale changed (`-Dfile.encoding=windows-949`, `LC_ALL=C`) | run with Korean input | output bytes are UTF-8 and identical | new | no |
| HK-016 | HOOK | stdout/stderr | run all above | stdout only JSON, stderr empty, exit 0 (except Claude malformed JSON) | S: `stderr === ''` | no |
| HK-017 | HOOK | cold start of `java -jar` | run | completes inside the host hook time limit (5 s on the test host); budget recorded | host-attestation spawn timeout 5000 | no |
| HK-018 | HOOK | hook jar | inspect contents | only the module's classes, Jackson (core, annotations, databind) and Jackson's own dependencies; no Spring, no JDBC driver, no secret or config file; hook code uses no network, file or environment access | new | no |
| HK-020 | HOOK | first argument `claude`, `codex`, `antigravity`; `Claude`, `gemini`, empty, missing, two arguments | run | the three enum values select the matching input/output dialect; any other value or a missing argument exits 2 with a usage message on stderr and no stdout; the vendor prefix of the stamped id follows the enum (`claude:`, `codex:`, `gemini:`) | decision 7m | no |
| HK-021 | HOOK | built jar | read the manifest and run `java -jar` with no other classpath | `Main-Class` set; runs on JDK 25 only with the jar (no extra dependency); on a JDK older than 25 it fails with a clear message instead of running | decision 7m | no |
| HK-019 | HOOK | harness helpers `validateIsolatedCodexSink`, `parseAgyToolStep` (AGS experiment code that lived in the test file) | n/a | dropped: not product behaviour | S13 helper, S14 | no |

## 14. E2E: deployed (test host, MCP Java SDK client + HTTP)

Runs against services started from the real artifacts (systemd units or `java -jar`) on the test host, PostgreSQL embedded or the host's PostgreSQL, plus an H2 run. Portal steps that need the user ("Ledger (test)" sync) are manual gates, reported as NOT_RUN until the user performs them.
| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| E2E-001 | E2E | clean host | OS prepare, `driver-register`, `secret-set`, `migrate`, project-add, code-set, grants, start both services | both `/healthz` UP, `mcp-check` passes | e2e.sh fresh install | yes |
| E2E-002 | E2E | seeded `e2e` project (category BUG with quoted/newline text, RELATION_KIND `test_relation`, retired category, grants for author, reviewer) | run the old MCP scenario as JUnit (MCW-025) | all steps of section 7 / 6 pass | e2e.mjs whole file; e2e.sh:S-O04 | yes |
| E2E-003 | E2E | deployed admin | run ADM-001..024 subset over HTTP | pass | admin-ui whole file; S-O05 | yes |
| E2E-004 | E2E | deployed hooks (3) | run HK-001..009 through the real launcher | pass | S-O02,S-O03 | no |
| E2E-005 | E2E | admin token and MCP bearer | compare | different values | e2e.sh:S-O01 | no |
| E2E-006 | E2E | race scenario of SVC-RACE-01 against the deployed server (two client connections, server-side delay hook disabled; stress 200 rounds) | run | invariant holds | e2e.sh:S-R01..S-R03 | yes |
| E2E-007 | E2E | after the scenario | re-run `migrate` and restart services | data digest (per table) identical, bearer and admin secrets unchanged, receipts replay | e2e.sh:S-I01..S-I05 | yes |
| E2E-008 | E2E | upgrade rehearsal | old checkout at `9fb62fe` installed, seeded with the old admin (project, categories from `category-legacy-fixture.json`, grants, defects incl. merged and UNRATED, tasks, conditions, links, cases), new version installed beside it, `import-legacy` | all MIG-001..026 assertions pass; old services still answer (rollback possible) | e2e.sh `--upgrade-from` | yes |
| E2E-009 | E2E | portal route (Cloudflare MCP portal) | call tools through the portal; change a code; | tool calls succeed; the open session receives `list_changed` (user observes); Cloudflare-specific behaviour exists only in configuration | plan step 6 | no |
| E2E-010 | E2E | new `@McpTool` shipped in a test build | restart, user presses sync in "Ledger (test)" | tool count rises by one and the tool is callable through the portal (manual gate) | plan step 6 | no |
| E2E-011 | E2E | systemd units | `systemctl show` | hardening directives of the current units are kept; both run as non-root; MCP binds 127.0.0.1 | plan: deployment | no |
| E2E-012 | E2E | whole run | scan logs | no secret value, no stack traces for expected errors | plan | no |
| E2E-013 | E2E | restart during traffic | stop/start MCP while a client holds a session | client reconnects, no data loss, receipts replay, no duplicate writes | plan | yes |
| E2E-014 | E2E | PostgreSQL run and H2 run of E2E-002 | run both | identical outcomes | user decision 2 | yes |
| E2E-015 | E2E | rollback rehearsal | restore the 0167b65 units and env backup | old service works against the untouched old schema | plan: rollback | no |
| E2E-016 | E2E | host with the deploy script (OS script, no Node) and a test domain | run the script in nginx mode | writes a reverse-proxy config with `server_name` for the MCP and admin hostnames, proxying to the loopback ports; the generated hostnames are also written into the Host allowlists of both apps (configuration, not env scraping); re-running leaves one config (idempotent); `nginx -t` passes when nginx is present, else NOT_RUN | e2e.sh:S-W01,S-W05; decision 7a | no |
| E2E-017 | E2E | same script, Cloudflare mode selected / not selected | run | with the mode: a cloudflared ingress file listing both hostnames; without it nothing Cloudflare-related is created or required; no `node` or `npm` is invoked in either mode | e2e.sh:S-W04; decision 7a | no |
| E2E-018 | E2E | admin with a Spring Boot SSL bundle configured from a test certificate | `GET /healthz` over HTTPS with allowed Host; with unlisted Host; plain HTTP to the same port | HTTPS answers 200; unlisted Host 403 `DNS_REBINDING_BLOCKED`; plain HTTP is refused; the session cookie is `Secure`; certificate and key files are 0600/0640, never printed; configuration only, no code path reads PEM files by hand | e2e.sh:S-W06,S-W07; decision 7a | no |
| E2E-019 | E2E | clean host, wrapper `one-click-install.sh` | run (also piped through stdin from another directory) | steps in order: OS preparation, `ledger-cli migrate`, `ledger-cli secret-set` prompts through the hidden console input; exit 0; services come up; no secret on any command line, in logs or in the environment of child processes | e2e.sh:S-I06,S-I07; decision 7b | yes |
| E2E-020 | E2E | installed host | run the wrapper again | idempotent: no schema change (SCH-002), existing secrets and tokens kept byte for byte, services keep running | e2e.sh:S-I06,S-I08; decision 7b | yes |
| E2E-021 | E2E | wrapper arguments `--google-client-secret x`, `--client-secret x`, any `--*secret*` option | run | rejected with usage (exit 2) and the value is never echoed or stored; the option does not appear in the usage text; ARCH scan (ARC-035) finds no script or CLI option that takes a secret value on argv | decision 7b | no |
| E2E-022 | E2E | `install.sh` on PostgreSQL with existing data | run before `migrate` | a dump file exists before the first `migrate` statement runs (file timestamp earlier than the first new `schema_history` row), mode 0600, in the configured backup directory, non-empty, restorable into an empty database (row digests equal); if the dump fails the install stops before `migrate`; dump output and credentials are not printed; on H2 the step is skipped with a message | e2e.sh:S-X04; decision 7c | no |

## 15. ARC: ArchUnit and source scans (ARCH level)

Rules come from the plan's "기준" section and the later user decisions. Allow-lists are explicit data in the test (a changed allow-list needs review).
| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| ARC-001 | ARCH | all main classes | scan | no `lombok.*` import or annotation | plan | no |
| ARC-002 | ARCH | main classes and Gradle/unit files | scan | no `setAccessible`; no `--add-opens`, `--enable-preview`; no preview feature | plan | no |
| ARC-003 | ARCH | main classes | scan | `java.lang.reflect` used only in allow-listed packages (none expected) | plan | no |
| ARC-004 | ARCH | main classes | scan | no `ProcessBuilder`, `Runtime.exec`, no `zt-exec`-like wrapper: nothing shells out to Node/bash/psql/systemctl/runuser | plan: no wrapping | no |
| ARC-005 | ARCH | Gradle `runtimeClasspath` of every module | resolve | none of picocli, Spring Shell, zt-exec, JPA/Hibernate, Liquibase, Flyway, Lombok, any JDBC driver | plan; user decisions 1,2 | no |
| ARC-006 | ARCH | main classes | scan | no `System.out`, `System.err`, `printStackTrace`; logging only through SLF4J (no `java.util.logging`, Log4j, Commons Logging direct use) | plan | no |
| ARC-007 | ARCH | main classes | scan | no `System.getenv`, no `@Value`, no OS branching (`os.name`, `File.separator` logic) | plan | no |
| ARC-008 | ARCH | main sources | source scan | no empty `catch`, no catch that only returns a default, no catch of `Exception`/`Throwable` outside the single conversion class and the process entry points | plan: no swallowed exceptions | no |
| ARC-009 | ARCH | modules | dependency check | `ledger-domain` depends on no other module and on no web, Jackson or Spring Web types (only Spring Data mapping annotations); `ledger-application` -> domain; `ledger-schema` has no code dependency; `mcp-server`, `admin-web`, `cli` -> application + domain; `admin-web` and `mcp-server` do not depend on each other; `cli` has no web dependency; `e2e` only through public clients | plan: module direction | no |
| ARC-010 | ARCH | main classes | scan | no `JsonNode`, `ObjectNode.put`, `Map<String,Object>` as a model/DTO (only the boundary deserializer package, if any, is allow-listed) | plan | no |
| ARC-011 | ARCH | history aggregate | scan | its repository exposes insert and read only (extends `Repository`, no `CrudRepository`, no delete/update/save-existing method, no `@Modifying` query touching history or `schema_history`); no domain method mutates a `HistoryEntry` | plan: append-only | no |
| ARC-012 | ARCH | main classes | scan | raw SQL (`@Query`, `JdbcClient`, `JdbcTemplate`, SQL string literals) only in classes of the `repository` package | plan: ANSI enforcement (1) | no |
| ARC-013 | ARCH | main classes | scan | `@Transactional` only on application services (class level, declared once); tool adapters, controllers, repositories carry none; no `TransactionTemplate` outside the batch coordinator | plan: common processing once | no |
| ARC-014 | ARCH | exceptions | scan | all domain exceptions extend the sealed `LedgerException`; exactly one class converts them to codes with an exhaustive `switch`; MCP result and `@RestControllerAdvice` both call it; no `getMessage().contains(...)` | plan | no |
| ARC-015 | ARCH | main classes | bytecode scan | no `switch` over `String` (string-keyed dispatch) in domain/application; entity, state, kind and scope are enums | plan: no string discrimination | no |
| ARC-016 | ARCH | main classes | scan | exactly one `MessageSource` bean; no user-facing literal in exception constructors (messages come from the bundles) | plan | no |
| ARC-017 | ARCH | configuration | scan | `ledger.*` settings bound only by `@ConfigurationProperties` records | plan | no |
| ARC-018 | ARCH | main classes | scan | request context via `ScopedValue`; no `ThreadLocal`; no non-final static fields, no static mutable collections | plan | no |
| ARC-019 | ARCH | domain/application | scan | no `Instant.now()`, `System.currentTimeMillis()`; time only from the injected `Clock` (`Clock.systemUTC` bean) | plan; user decision 4 | no |
| ARC-020 | ARCH | token comparison code | scan | secrets compared only with `MessageDigest.isEqual` (or Spring Security); no `String.equals`/`==` on secret values | plan | no |
| ARC-021 | ARCH | `@McpTool` adapters | scan | each method calls exactly one application-service method, no repository access, no business branching; controllers likewise | plan: adapters do boundary conversion only | no |
| ARC-022 | ARCH | domain aggregates | scan | records/enums/sealed types only; no setters; no boolean component; no `LocalDateTime`/`ZonedDateTime` | plan; user decisions 4,5 | no |
| ARC-023 | ARCH | `@CommonCode` usages | scan | applied only to String/enum parameters and only with the three valid groups | plan | no |
| ARC-024 | ARCH | main classes | bytecode scan | none of: no-arg `LocalDateTime.now/LocalDate.now/ZonedDateTime.now/OffsetDateTime.now`, `ZoneId.systemDefault`, `TimeZone.getDefault`, `Clock.systemDefaultZone`, `java.util.Date`, `Calendar.getInstance`, `System.currentTimeMillis`; the single UTC JDBC converter class is the only allow-listed user of zone conversion | user decision: all time UTC | no |
| ARC-025 | ARCH | structure | scan | no static-only utility classes (`*Util`, `*Helper`, `*Manager`) outside an allow-list of value factories; CLI commands are one `Command` enum dispatched from `ApplicationArguments` (no class-per-script structure); no Node-shaped naming | plan: no Node structure | no |
| ARC-026 | ARCH | repository tree | file scan | zero `*.mjs` files, zero executable references to `node`, `npm`, `dbhub` in scripts, units, Gradle files; `mcp/dbhub.toml`, `deploy/render-tools.sh`, `bin/*.mjs`, `web/*`, legacy `sql/*.sql`, `tests/*.mjs`, `tests/*.sh` removed (gate applies after the new implementation is verified) | plan: completion criteria | no |
| ARC-027 | ARCH | main classes | scan | no `DriverManager` usage; driver classes loaded only by the one driver-loader class (`URLClassLoader`, `Driver`, `SimpleDriverDataSource`, Hikari) | user decision 2 | no |
| ARC-028 | ARCH | build | inspect | Java 25 toolchain, `-Werror` on every module, dependencies versioned by the Boot BOM or carrying a "version and reason" comment | plan | no |
| ARC-029 | ARCH | classpath | scan | Spring Data JDBC only, no JPA | plan | no |
| ARC-030 | ARCH | apps | scan | `mcp-server` and `admin-web` never apply DDL; only `ledger-cli` references `SchemaMigrator`; the apps hold only the startup "schema up to date / UTC" guard | plan | no |
| ARC-031 | ARCH | README, docs, release notes | text scan | no list of "supported databases" or vendor names as supported; text only says a JDBC driver can be attached, SQL is ANSI only, no DBMS-specific feature is used; English and Korean versions both | user decision | no |
| ARC-032 | ARCH | domain/application main code | scan | no vendor/provider/cloud names (`google`, `cloudflare`, `claude`, `codex`, `gemini`, `antigravity`) outside configuration samples, hooks and tests (vendor independence) | global rule: vendor independent | no |
| ARC-033 | ARCH | `admin-web` | scan | does not depend on the ledger write workflow services (save, transition, record_fix, record_case, link, correct, batch, review); allowed: project, grant, common-code, driver services and read services | admin-ui:A5 (rewritten) | no |
| ARC-034 | ARCH | module `ledger-client-hook` | dependency and import scan | exactly one module/jar for all three clients; depends only on the JDK and Jackson; no Spring, no other module, no JDBC; packages show a client enum, not three client-specific code trees | decision 7m | no |
| ARC-035 | ARCH | scripts (`deploy/*`), unit files, CLI argument model | scan | no option, positional argument or env assignment carries a secret value on argv (`--*secret*`, `--*token*`, `--*password*`); secrets enter only through `secret-set` hidden input or the secret store | decision 7b | no |
| ARC-036 | ARCH | main and test sources of `ledger-database`, `ledger-cli`, `ledger-client-hook` | bytecode scan (database: no catch block for `java.sql.SQLException` outside the `driver` package) and source scan | no empty catch; no catch of `SQLException` outside the driver boundary (`RegisteredDriver`: the JDBC call that throws it is turned into DRIVER_INVALID with the cause); no catch of `Exception`, `Throwable`, `RuntimeException` or `Error` except the CLI test entry `CliExtension`, which plays the CLI boundary; principles 3.4 and checklist 21 | development principles 3.4 (user 2026-11-11: handle exceptions well); numbered after ARC-035 because ARC-034 and ARC-035 are taken above | no |
| ARC-037 | ARCH | main and test sources of the same three modules | bytecode scan and source scan | no `variable.equals("literal")` (literal first, or `Objects.equals` for two variables); no `==` or `!=` against a string literal; no `switch` on a string; no `intern()`; no `new String(` (the one test helper `Run` of the hook turns captured process bytes into text and is named in the scan); principles 3.3 and checklist 26 | development principles 3.3 (user 2026-11-11: string comparison with equals, literal first) | no |

## 16. ANSI: static check of SQL

Scope: (a) every SQL string literal in the `repository` package, (b) every `V{n}__*.sql` file. Matching is token-based (word boundary, case-insensitive, after stripping comments and string literals), so `limit_value` or a string `'top'` is not a violation.
| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| ANSI-001 | ANSI | repository SQL strings | scan for the plan's banned tokens | zero hits for: `LIMIT`, `ROWNUM`, `TOP`, `ILIKE`, `~`/`~*`/`!~` regex operators, `::` casts, `RETURNING`, `ON CONFLICT`, `MERGE`, `ON DUPLICATE KEY`, `FOR UPDATE`, `FOR SHARE`, `WITH RECURSIVE`, `jsonb`/`json_*`/array functions (`unnest`, `array_agg`, `string_agg`), `now()`, `gen_random_uuid()`, `pg_advisory*`, `nextval/currval/NEXT VALUE FOR`, partial-index `WHERE` on `CREATE INDEX`, `NULLS NOT DISTINCT`, row-value comparison `(a,b) < (?,?)` | plan: ANSI list | no |
| ANSI-002 | ANSI | every `V{n}__*.sql` | same scan plus DDL rules | zero hits of the ANSI-001 tokens | user decision 1 | no |
| ANSI-003 | ANSI | every V file | scan | no `CREATE TRIGGER`, `CREATE PROCEDURE`, `CREATE FUNCTION`, `CREATE VIEW`, `CREATE SEQUENCE`, `IDENTITY`, `GENERATED ... AS IDENTITY`, `SERIAL`, `AUTO_INCREMENT`, `DO $$`, `CREATE EXTENSION`, `CREATE ROLE`; only whitelisted statement types: `CREATE TABLE`, `CREATE INDEX`, `ALTER TABLE ... ADD CONSTRAINT`, `INSERT` (seeds), `GRANT` | plan; user decision 1 | no |
| ANSI-004 | ANSI | PostgreSQL and H2 after `migrate` | query each catalog (`pg_trigger`/`pg_proc` for the schema; `INFORMATION_SCHEMA.TRIGGERS`/`ROUTINES`) | zero triggers, zero routines, zero sequences owned by the schema | plan: completion criterion | yes |
| ANSI-005 | ANSI | every identifier in V files | check | lowercase, at most 30 characters, no quoted identifiers, no reserved words (`condition`, `release`, `user`, `type` renamed or checked against a reserved-word list) | AGS decision (identifiers lowercase, 30 chars) | no |
| ANSI-006 | ANSI | column types in V files | check | only from the allowed set (`VARCHAR(n)`, `CLOB`, `INTEGER`, `BIGINT`, `TIMESTAMP`, `DECIMAL`); no BOOLEAN/BIT, no `TIMESTAMP WITH TIME ZONE`, no `UUID`, `JSON(B)`, `TEXT`, `SERIAL` | user decisions 4,5 | no |
| ANSI-007 | ANSI | UNIQUE / PRIMARY KEY definitions | check | every column of a UNIQUE constraint is `NOT NULL` (normalised key columns `active_key`, `link_key`); no partial or expression index | plan: unique rule | no |
| ANSI-008 | ANSI | CHECK constraints in V files | check | no function calls, no regex; only IN-lists, comparisons, NULL-ness (matches REPO-004) | plan | no |
| ANSI-009 | ANSI | dynamic SQL builders in repositories | scan and run | values are always bind parameters; no string concatenation of caller input into SQL (also the injection corpus of REPO-027 and MCR-S06) | security | yes |
| ANSI-010 | ANSI | the checker itself | feed a corpus: each banned token alone, inside a comment, inside a string literal, as a substring of an identifier, in lower/upper/mixed case, split over lines | exactly the real uses are reported (mutation proof: removing a rule makes a seeded violation test fail; clean SQL has no false positive) | test-engineering | no |
| ANSI-011 | ANSI | candidate additional bans (proposed for ratification, not in the plan): `FETCH FIRST`, `OFFSET`, `\|\|` concatenation, `CURRENT_TIMESTAMP`/`SYSDATE`/`GETDATE`, `LIKE` without `ESCAPE`, `LENGTH()` | run | reported as warnings listing hits; promoted to failures only after ratification | plan: time from `Clock` | no |
| ANSI-012 | ANSI | V files | check | no session/vendor settings (`SET ...`, `ALTER SESSION`, `PRAGMA`), no `COMMENT ON` | plan | no |
| ANSI-013 | ANSI | V files | run on PostgreSQL and H2 | the same text executes on both without a branch (no `dbms:`-style conditional, no per-DB file variant) | plan: no `dbms:` branch | yes |
| ANSI-014 | ANSI | every V file and every repository SQL string | scan | no `DEFAULT CURRENT_TIMESTAMP`, `DEFAULT LOCALTIMESTAMP`, `DEFAULT CURRENT_DATE`, `DEFAULT NOW()`, `SYSDATE`, `GETDATE`, or any other DB-side time default or time function in SQL (`CURRENT_TIMESTAMP`, `LOCALTIMESTAMP`, `CURRENT_DATE`, `CURRENT_TIME`); catalogue check: no timestamp column has a column default; every time value is bound from the application UTC `Clock` | user decision: times from app UTC Clock | yes |

## 17. MIG: legacy import (`ledger-cli import-legacy`)

Source = the old PostgreSQL schema `ledger` as left by the old installer at `9fb62fe` (built in tests from the old `sql/*.sql` copied into the test resources, plus the data written by the fixtures below). Target = new schema; source and target may be the same PostgreSQL database or different (PG -> H2). The source connection is read-only.
| ID | Level | Given | When | Then | Source | DBMS |
|---|---|---|---|---|---|---|
| MIG-001 | MIGRATION | rich source (project, task, defect incl. merged and UNRATED, defect_fix, defect_case, defect_task, defect_relation, condition, condition_link, session_grant incl. revoked/expired, history incl. IMPORT/LEGACY ops, request_receipt, common_code) | import | row count per table equals source; no extra rows; command prints counts and exit 0 | plan: 표별 행 수 | yes |
| MIG-002 | MIGRATION | same | per-table digest | SHA-256 over canonical row serialisation ordered by key (all columns, instants as UTC `Z`, empty-string rule of SVC-NORM applied identically on both sides and documented) equal source vs target; ids of history, fix, case, grant preserved | plan: 요약값 | yes |
| MIG-003 | MIGRATION | history rows with `changes`, `legacy_ref`, IMPORT/LEGACY_NOTE/LEGACY_CHANGE, `actor_assurance` LEGACY_*, `login`, `request_id`, `corrects_id` | import | every field equal (JSON equality of `changes`); `corrects_id` references intact; **`before_data` is NULL on every imported row** (never fabricated) | user decision 3; plan | yes |
| MIG-004 | MIGRATION | request_receipt rows with stored responses | import | rows, responses and old `payload_sha256` kept; every imported row has `origin=LEGACY`; counts equal; a replay with any imported request id is refused with `REQUEST_ID_LEGACY` (SVC-RCP-15) and never returns the stored response or writes | plan; sql `_reserve`; decision 7i | yes |
| MIG-005 | MIGRATION | live, revoked and expired grants | import, then whoami and writes | live grants usable, revoked and expired refused, `active_key` set (`session\|project\|scope` for live, `revoked:<id>` for revoked); counts equal | plan | yes |
| MIG-006 | MIGRATION | `created_by` values incl. `user:owner`, `unknown:legacy` and creators of re-linked rows | import | values copied as in the source (not recomputed): creators of defects, tasks, conditions, links; re-linked rows keep the current incarnation's creator; ambiguous legacy keys stay `unknown:legacy` | e2e.sh:S-U16..S-U21 (rewritten) | yes |
| MIG-007 | MIGRATION | defects up to `D-0100` non-contiguous plus merged ones; tasks `E2E-T-1`, `OLD-T-1` | import | project defect counter = maximum numeric suffix, task counter = maximum numeric suffix of `<CODE>-T-NNNN` ids (0 when none); next auto-assigned id is max+1 and collides with nothing; counters of `id_counter` for history, fix, case, grant = max existing id | plan: counters at import | yes |
| MIG-008 | MIGRATION | source schema | import | source untouched: per-table digests, object lists and `_migration` rows identical before and after; a write attempt on the source connection fails (read-only account) | plan: old schema untouched | yes |
| MIG-009 | MIGRATION | import run twice | second run | refuses (target not empty) with exit 1; no duplicates | plan | yes |
| MIG-010 | MIGRATION | failure injected after table k | import | target has zero imported rows (single transaction) and a rerun succeeds | plan | yes |
| MIG-011 | MIGRATION | `category-legacy-fixture.json` (14 canonical categories) + a custom `OLDBUG` + severities + relation kinds | import | all `common_code` rows kept with state, sort order, ko and en texts; `CON` en name `Concurrency and atomicity`, `EXC` `Error cause suppression or substitution`; unknown category `OLDBUG` has English = Korean fallback; 14 known categories have English text different from Korean | e2e.sh:S-U11..S-U15 | yes |
| MIG-012 | MIGRATION | defect with severity `UNRATED` | import, then edit without severity; edit with `P3` | stays `UNRATED` until written; edit without rating `CODE_INVALID` (`defect.severity = UNRATED`); same edit with rating accepted, version 2 | e2e.sh:S-U22..S-U24 | yes |
| MIG-013 | MIGRATION | defect merged before the import | `ledger_defect(target)` | cases include `merged_behavior` of the merged defect with its observation text | e2e.sh:S-U25 | yes |
| MIG-014 | MIGRATION | links with the same target text in task and defect tables, ids containing `\|`, same-kind collisions | import | all rows imported without key collisions; `link_key` distinguishes kinds; nothing dropped | e2e.sh:S-U17..S-U20 | yes |
| MIG-015 | MIGRATION | 4000-character Korean text, quotes, newlines | import | digests equal (no truncation, no re-encoding) | REPO-031 | yes |
| MIG-016 | MIGRATION | `timestamptz` source values with sub-millisecond precision and mixed session zones | import | equal as instants; stored UTC; output ends in `Z` | user decision 4 | yes |
| MIG-017 | MIGRATION | merge chain where the child has a lower id than its target | import | succeeds (insert order or deferred constraint handled without DBMS-specific syntax); `merged_into` intact | 10_tables DEFERRABLE (replaced) | yes |
| MIG-018 | MIGRATION | after import | create, edit, transition, fix, verify, case on imported defects | version continues (`expected_version` from import), new history ids exceed all imported ids, no duplicate key anywhere | plan | yes |
| MIG-019 | MIGRATION | tampered target row after import | the command's verification step | exit 1 naming the table, count and digest | plan: 대조 | yes |
| MIG-020 | MIGRATION | source DSN password | run | taken from the secret store; never printed or logged | plan | no |
| MIG-021 | MIGRATION | source without the `defect_case`/`common_code`/`created_by` migrations (older than `9fb62fe`), or missing schema | import | exit 1, message names the missing step; nothing written | e2e.sh:S-U01 (rewritten) | yes |
| MIG-022 | MIGRATION | `before_data`/`changes` of imported rows through the read tool | `ledger_history(include_before=true)` | `changes` present as imported, `before_data` null | user decision 3 | no |
| MIG-023 | MIGRATION | (a) source rows with `''` in nullable text columns; (b) source rows with `''` in NOT NULL text columns (`purpose`, `success_condition`, e.g. `OLD-T-UNKNOWN`) | import | (a) nullable columns normalised to NULL, and the digest comparison applies the same normalisation to both sides; (b) the import reports every offending row (table, key, column) on stdout, exits 1 and writes nothing (single transaction, SVC-NORM-02 stays valid); no value is invented to make the import pass; after the operator corrects the source rows the import succeeds | user decision 6; decision 7j | yes |
| MIG-028 | MIGRATION | source entity ids (defect, task, condition) containing a lower-case letter, and `\|`-containing ids such as `OLD-PIPE-C\|A` | import | upper-case ids with `\|` import unchanged; ids that violate the upper-case id regex are reported (table, key) and the import stops with exit 1 and no write, never lower/upper-cased silently (P: same stop-and-report rule as MIG-023; the plan does not state this case) | decision 7m; MIG-023 | yes |
| MIG-024 | MIGRATION | fixture builder | build source from `category-legacy-fixture.json`, old admin-written data (`old` project, OLD-D-0001..3, tasks, conditions, links, relinks, cases) | fixture loads in the source test database and is reused by MIG-001..023 and E2E-008 | e2e.sh `--upgrade-from` seed block | yes |
| MIG-025 | MIGRATION | PostgreSQL source -> H2 target and PostgreSQL -> PostgreSQL (same database, new schema) | MIG-001..003 | pass on both targets | plan; user decision 2 | yes |
| MIG-026 | MIGRATION | rollback | stop new services, restore old units | old data and old services unchanged (MIG-008 + E2E-015) | plan: rollback | no |
| MIG-027 | MIGRATION | old `timestamptz` values (including `2026-11-05 10:00:00.123456+00`, DST-ambiguous and KST-midnight instants) read with the old database session zone set to UTC, `Asia/Seoul` and `America/New_York` in turn | import | the epoch value (seconds + micros) of every imported timestamp equals the source epoch value (`EXTRACT(EPOCH ...)` computed on the source) in all three runs; target rows are identical across the three runs | user decision: UTC identical Instant | yes |

## 18. Reconciliation: every old ID -> new ID or dropped

Disposition: **kept** = same behaviour, new mechanism; **rewritten: reason** = the old case asserted a PostgreSQL/DBHub/Node mechanism and is restated as the behaviour the user relies on; **dropped: reason**; **UNCLASSIFIED** = the plan and decisions do not say what happens (listed in 18.5, no guess made).

### 18.1 `tests/e2e.mjs`
| Old | Check | New | Disposition |
|---|---|---|---|
| E-01 | tool list has every ledger tool | MCW-001, MCC-001 | kept (22 tools now) |
| E-02 | no raw SQL tool on the writing logins | MCW-001, MCR-011 | rewritten: no per-login tools; no SQL tool at all |
| E-03 | ungranted author refused | SVC-GATE-03 | kept |
| E-04, E-05 | author id without session; `user:<other>` | SVC-GATE-02, DOM-002 | kept |
| E-06 | whoami lists grants | MCW-010, DOM-001 | rewritten: `session_user` becomes the channel's login label |
| V1 (description) | reviewer instructions text | MCW-017 | rewritten: `v_defect.fixed_by` becomes `ledger_defect.fixed_by` |
| K1 (removed) | `ledger_codes` is removed | MCR-011, MCR-K01 | rewritten: `ledger_codes` is now the 12th read tool; user relies on codes being visible |
| K1 (groups) | only three extensible groups | SCH-016, MCR-K01 | rewritten: read from seed/tool, not from SQL |
| K1 (categories, severities) | codes rendered in descriptions with escaped text; inactive UNRATED omitted | MCC-012, MCW-023 | rewritten: ToolCatalog fills schema/description; no template substitution |
| K2 | relation allowed_values equal DB codes | MCW-016, MCC-005, SVC-LNK-10 | kept |
| H1 | optional `_hostAttestation`, no `author_id` property | MCW-002 | kept |
| H2 (save, whoami) | missing attestation refused | MCW-003, SVC-GATE-01 | kept |
| H3 | legacy `author_id` accepted and ignored; alone cannot authorise | MCW-004, MCW-005, MCW-006 | rewritten: no DBHub; unknown args otherwise rejected |
| K3, K4, K5 | severity required; UNRATED refused; unknown category | SVC-SAV-02, -03, -04; DOM-021 | rewritten: structured `details.valid` instead of zod/trigger text |
| E-07..E-10 | next ids, replay, other payload, second id | SVC-IDS-01, SVC-RCP-01, -02, SVC-SAV-01 | kept |
| E-11 | foreign id prefix | SVC-IDS-02 | kept (`ID_PREFIX_MISMATCH`) |
| E-12, E-13 | stale version; edit with current version | SVC-SAV-06, -05 | kept |
| E-14 | defect cannot change project | SVC-SAV-09 | kept |
| K6, K7, K8 | unknown state, link kind, CI conclusion | SVC-TRN-01, SVC-LNK-04, SVC-FIX-02, MCW-014, MCW-016 | rewritten: `VALIDATION_FAILED` with allowed list instead of zod text |
| E-15 | SOURCE_FIXED without fix | SVC-TRN-02, DOM-010 | kept |
| V1 (fix) | record_fix without CI moves to SOURCE_FIXED | SVC-FIX-01 | kept |
| V3 | verification without CI still needs evidence | MCW-015, SVC-FIX-10 | rewritten: Jakarta group is the source of truth |
| E-16 | author tool cannot set VERIFIED | MCW-014, MCW-019 | rewritten: enum + channel |
| E-17, E-18 | fixing session cannot verify; verify needs grant | SVC-TRN-06, -07 | kept |
| V1 (verify) | another session verifies without CI via reviewer login | SVC-TRN-08 | rewritten: reviewer channel, `login` value kept |
| E-19, E-20 | link; correct | SVC-LNK-01, SVC-COR-01 | kept |
| E-21..E-28 | batch: save+link, replay, rollback, nothing written, grants, no verify via author, unknown keys, missing version | SVC-BAT-01..06 (E-23/24 also SVC-BAT-03; E-26 SVC-GATE-04; E-27/28 SVC-BAT-06) | kept; unknown-key text rewritten as `VALIDATION_FAILED` path |
| C1..C15 | observed cases | SVC-CAS-01..16 (C3 rewritten: DB CHECK `defect_case_observation_check` -> `VALIDATION_FAILED`; C12..C14 -> MCR-D02) | kept |
| P1 | `created_by not editable` | SVC-SAV-10 | rewritten: unknown field rejection |
| P2 | task, condition, links in one batch | SVC-BAT-09, SVC-IDS-06 | kept |
| P3 | `created_by` per table | SVC-PRV-01, REPO-005, REPO-006 | kept |
| H2 (8 tools) | transition, record_fix, record_case, link, correct, batch, review, review_correct need attestation | MCW-003 | kept |
| P4 | fix keeps its author column | SVC-FIX-13 | kept |
| P5 | creator preserved, `fixed_by` exposed | SVC-FIX-12, SVC-SAV-19, SVC-PRV-03, MCR-D04 | kept |
| E-29 | reader sees the verified defect | MCR-G07 | rewritten: via `ledger_get`, not `v_defect` SQL |
| E-30 | history keeps author, login, op | MCR-H03, SVC-TRN-08 | rewritten: via `ledger_history` |
| E-31 | reader cannot write through SQL | MCR-010, MCR-011 | rewritten: no SQL tool exists; read tools never modify |
| E-32 | reader cannot grant | MCC-001 (no grant tool), SVC-GRT-01 | rewritten: grants only through admin/CLI services |
| V2/V5 loop (create fixture, record CI, own fix cannot verify, other-session fix, verify independent of CI, metadata kept) | SVC-FIX-10, SVC-FIX-05 | kept |
| V6 | creator/fixer split | SVC-FIX-12 | kept |
| V1 (attention) | no `VERIFIED_WITHOUT_CI` | SVC-FIX-11, MCR-L04 | rewritten: via `ledger_attention` |
| V7 | task re-verification flow | SVC-TRN-13, DOM-017, DOM-018 | kept |

### 18.2 `tests/e2e.sh` and `tests/upgrade-atomicity.sh`
| Old | Check | New | Disposition |
|---|---|---|---|
| S-U01 | baseline has `defect_case` | MIG-021, MIG-024 | rewritten: source-schema precondition |
| S-U02 | canonical category fixture seeded | MIG-024, MIG-011 | kept as fixture |
| S-U03 | installer failure at 16/17/20 rolls back the whole schema | SCH-004, SCH-005, SCH-020, SCH-021 | rewritten: PG DDL is transactional, H2 is not; contract is the appended `schema_history` rows, retry of the failed version and refusal to proceed before its latest outcome is SUCCESS |
| S-U04, S-U05 | concurrent old writers wait for the upgrade (rollback/commit mode) and are then refused | SCH-009, SCH-010, SVC-SAV-04 | rewritten: advisory-lock gate is gone; app refuses to start on a pending schema |
| S-U06 | reapply after the gated upgrade | SCH-002 | kept |
| S-U07 | upgrade keeps every row | MIG-002 | rewritten: import digests |
| S-U08, S-U09, S-U10 | steps `defect_case`/`common_code` recorded; empty `defect_case` | SCH-001 | dropped: the `_migration` step markers and the in-place upgrade are removed; `schema_history` replaces them |
| S-U11..S-U15 | categories moved unchanged; 14 translations; CON, EXC; fallback | MIG-011 | rewritten: import copies `common_code` |
| S-U16..S-U21 | creator backfill rules | MIG-006, MIG-014, REPO-021 | rewritten: the backfill SQL is removed; import must copy the already-backfilled values |
| S-U22..S-U24 | UNRATED kept, edit refused, edit with rating | MIG-012 | kept |
| S-U25 | merged-before-upgrade defect shows as case | MIG-013 | kept |
| S-F01 | empty-category placeholder | MCC-013, SVC-CC-10 | rewritten: message from bundle |
| S-F02 | fresh seed has `regression_of` | SCH-016, SVC-LNK-09 | kept |
| S-O01 | admin token differs from agent token | ADM-008, E2E-005 | kept |
| S-O02, S-O03 | hook tests run | HK-001..HK-018, E2E-004 | rewritten: Java hooks |
| S-O04 | MCP scenario | MCW-025, E2E-002 | kept |
| S-O05 | admin UI tests | ADM-*, E2E-003 | kept |
| S-R01..S-R03 | case vs merge race (advisory-lock choreography) | SVC-RACE-01, E2E-006 | rewritten: invariant-based, no advisory lock |
| S-D01 | author login cannot verify | SVC-GATE-04, MCW-019 | rewritten: channel enum |
| S-D02, S-D03 | DB guard: VERIFIED needs evidence and fix | SVC-TRN-05 | rewritten: service-level guard |
| S-D04, S-D05 | author login cannot write tables / add cases directly | none | dropped: no per-role DB logins; plan accepts that the DML account may write (section "줄어드는 것") |
| S-D06 | history append-only for the author login | REPO-023, REPO-038, ARC-011 | rewritten: GRANT + insert-only repository |
| S-D07 | author login cannot call `grant_session` | MCC-001, ARC-033 | rewritten: no grant tool, no admin workflow dependency |
| S-D08 | reader login is read-only | MCR-010 | rewritten |
| S-D09 | history refuses delete even for the superuser (trigger) | REPO-023 | dropped (superuser part): triggers are forbidden; protection is GRANT and the missing code path |
| S-C01 | fixed CHECKs retained | REPO-003 | kept |
| S-C02 | only three code-use mappings | ARC-023, SCH-016 | rewritten: `@CommonCode` replaces the table |
| S-C03 | owner rows stamped `user:owner` | SVC-PRV-02, ADM-043 | kept |
| S-C04 | direct UPDATE cannot change `created_by` | REPO-006 | rewritten: `@ReadOnlyProperty` (no trigger) |
| S-C05 | inactive category refused at the DB boundary | SVC-SAV-04 | rewritten: service-level |
| S-C06 | unknown relation names valid codes | SVC-LNK-04 | kept |
| S-C07 | fresh `regression_of` link | SVC-LNK-09 | kept |
| S-C08 | eight `created_by` NOT NULL | REPO-005 | kept |
| S-I01, S-I02 | reapply preserves rows and creators | SCH-002, E2E-007 | kept |
| S-I03 | `created_by` migration recorded once | none | dropped: one-time backfill step no longer exists |
| S-I04, S-I05 | token and admin token kept on reapply | E2E-007, SEC-009 | rewritten: secret store |
| S-I06..S-I08 | one-click installer idempotent, piped, preserves tokens | E2E-019, E2E-020, E2E-021 | rewritten: thin OS wrapper (OS prep, `migrate`, `secret-set`); the argv secret option is removed (new case E2E-021) |
| S-W01 | `setup-domain` updates allowlists in env | E2E-016, ADM-027, MCC-016 | rewritten: allowlists are configuration written by the deploy script |
| S-W02, S-W03 | custom host accepted, evil host 403 | ADM-027 | kept |
| S-W04, S-W05 | cloudflared ingress file; nginx reverse-proxy config | E2E-017, E2E-016 | kept (OS script, no Node; Cloudflare optional) |
| S-W06, S-W07 | Origin TLS certificate files; native HTTPS healthz | E2E-018 | rewritten: Spring Boot SSL bundle; certificate generation itself UNCLASSIFIED (18.5 #1) |
| S-W08 | Google OAuth redirect | ADM-030 | rewritten: generic OIDC, provider is configuration |
| S-X01..S-X03 | data kept, cases kept, cases recorded | E2E-007, SVC-CAS-01 | kept |
| S-X04 | backup taken before re-apply | E2E-022 | rewritten: PostgreSQL dump before `migrate` in the ops script |

### 18.3 `tests/admin-ui.mjs`
| Old | Check | New | Disposition |
|---|---|---|---|
| A1 | health, CSP, static assets, SPA element ids, `isComposing`, `isSafeHttpUrl`, reactivation text, `s1.className` | ADM-001..005 | rewritten: Thymeleaf pages; `isComposing` (IME key handling) and `className` SPA internals dropped: no SPA |
| A2 | unauthenticated 401 | ADM-007 | kept |
| A3 | agent token rejected | ADM-008 | kept |
| A4 | CSRF header and Sec-Fetch-Site | ADM-013, ADM-014 | rewritten: Spring Security CSRF token + `SameSite=Strict` cookie; dropped: the custom `X-Requested-With`, `Sec-Fetch-Site` and `Origin` checks (replaced by the two standard mechanisms) |
| A5 | agent workflow endpoints 403 | ADM-015, ARC-033 | rewritten: endpoints do not exist |
| A6 | login cookie flags | ADM-009 | kept |
| A7 | read endpoints (overview incl. MCP health, projects, defects, detail, tasks paging, history, grants, codes) | ADM-016, ADM-017, ADM-010, ADM-045, ADM-046 | rewritten: pages; MCP health = `RestClient` GET of the configured URL |
| A8 | grant and revoke | ADM-020, ADM-021, SVC-GRT-01..03 | kept |
| A9 | code delete refused; code-set re-renders tools; retire | ADM-022..025, MCC-003 | rewritten: no `tools.toml` render/sync steps, `list_changed` instead |
| A10 | `X-Forwarded-Proto` adds Secure | ADM-026 | kept |
| A11 | `/?token=` bootstrap | ADM-038 | dropped: URL token replaced by login form (flagged for confirmation) |
| A12 | Google OAuth 404 / 302 | ADM-029..031 | rewritten: OIDC |
| (untested old rules) | `TOO_MANY_AUTH_FAILURES`, `DNS_REBINDING_BLOCKED`, `PAYLOAD_TOO_LARGE`, `INVALID_PATH`, `INVALID_*` validations | ADM-028, -027, -034, -035, -019..-023 | carried although the old suite never asserted them |

### 18.4 Hook tests
| Old | New | Disposition |
|---|---|---|
| claude-hook CH-01 (10 tools) .. CH-07 | HK-001..HK-009 | rewritten: executable jar `ledger-client-hook`, argument `claude` |
| host-attestation S01..S07, S09, S11, S12 | HK-001..HK-009, HK-011, HK-012, HK-020 | rewritten: same jar, argument `codex` / `antigravity` |
| S08, S10 | HK-010, HK-013 | rewritten |
| S13 | HK-010 (matcher part) | rewritten; the sink-name validator is harness code, dropped (HK-019) |
| S14 | HK-019 | dropped: AGS stream-json collector test helper, not product behaviour |
| `category-legacy-fixture.json` | MIG-024, MIG-011 | kept as fixture |

### 18.5 Open items after the second-round decisions
Items 1 to 13 of the former unclassified list were decided by the main agent (decision 7 in section 1.4) and are now concrete cases: setup-domain/nginx/cloudflared/SSL bundle (E2E-016..018), one-click wrapper (E2E-019..021), PostgreSQL dump before migrate (E2E-022), MCP health (ADM-045, ADM-046), CSRF (ADM-013, ADM-014; custom `Sec-Fetch-Site`/`Origin` checks dropped), FAILED schema file retry (SCH-005, SCH-020, SCH-021), driver registration yaml and setup mode (DRV-009, DRV-012, DRV-020..023, ADM-042), `match` ANY/ALL (MCR-S02), LEGACY receipts (MIG-004, SVC-RCP-15, REPO-041), legacy `''` (MIG-023, SVC-NORM-02), `category-add` dropped (CLI-006), three behaviours "unchanged by design" (DOM-019, SVC-FIX-16, SVC-COR-05), plan fixes (REPO-015, REPO-039, REPO-040, SVC-RACE-01, SVC-RACE-09, DOM-036, REPO-032, hook jar HK-001..HK-021, ARC-034).
Still unclassified or provisional (not decided, not guessed):
1. Generation of the Origin TLS certificate (the old script created a 10-year ECDSA certificate): decision 7a covers the SSL bundle configuration only; E2E-018 uses a test certificate and says nothing about who creates it in production.
2. Legacy entity ids that violate the upper-case id regex (MIG-028 states a stop-and-report rule marked P) and the exact regex constant (DOM-036 only requires that every id used by the old fixtures passes).
3. Names and keys marked `(P)` (read-tool input keys, `match`, error tokens of the registry) are provisional literals, not open behaviour.
