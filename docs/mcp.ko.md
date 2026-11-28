# MCP 도구와 첨부

[English](mcp.md) · [가이드 목차](guide.ko.md)

## MCP 도구

서버는 `/mcp`(기본 `127.0.0.1:8765`)에서 Streamable HTTP로 MCP를 제공하고 `/healthz`에 응답합니다. 호출자는 `ledger.mcp.token`을 제시합니다.
모든 도구는 `ApiResult.v1` 봉투(`schemaVersion` `"1.0.0"`, `ok`, `data`, `error`)로 응답합니다. 모든 도구 입력은 닫혀 있고 평탄하여(원시 SQL 질의 인자나 비정형 맵 없음), 알 수 없는 인자는 거절됩니다. 객체나 배열 인자는 그 JSON 값으로 보내야 하며, 텍스트 `null`은 인자가 없다는 뜻이 아니라 하나의 텍스트입니다. 선택 필드의 JSON `null`은 생략한 것으로 보며, 선택 텍스트 필드는 빈 문자열로 지웁니다.

쓰기 도구(인증된 작성자와 유효한 부여가 필요합니다). `ledger_whoami`를 뺀 모든 쓰기 도구는 필수 인자 `operationKey`를 받습니다.

| 도구 | 채널 | 내용 |
|---|---|---|
| `ledger_whoami` | author | 호출자 신원을 확인하고 유효한 프로젝트 부여를 조회합니다(읽기 전용) |
| `ledger_issue_save` | author | 명시적 파라미터(`project_id`, `title`, `category`, `severity`, `symptom`, `root_cause`, `owner_actor_id`, `id`, `expected_version`, `reason`)로 이슈를 만들거나 수정합니다 |
| `ledger_spec_save` | author | 명세를 만들거나 수정합니다(`project_id`, `parent_id`, `title`, `summary`, `acceptance_criteria`, `priority`, `owner_actor_id`, `origin_ref`, `origin_quote`, `id`, `expected_version`, `reason`) |
| `ledger_task_save` | author | 작업을 만들거나 수정합니다(`project_id`, `title`, `purpose`, `success_condition`, `owner_actor_id`, `origin_ref`, `origin_quote`, `id`, `expected_version`, `reason`) |
| `ledger_criterion_save` | author | 마일스톤 기준을 만들거나 수정합니다(`project_id`, `id`, `milestone_name`, `goal_label`, `code`, `criterion_text`, `expected_version`, `reason`) |
| `ledger_transition` | author | 작성자 채널 상태를 바꿉니다(`OPEN`, `RESOLVED`, `NOT_AN_ISSUE`, `DISPUTED`, `MERGED`, `DRAFT`, `PLANNED`, `ACTIVE`, `BLOCKED`, `COMPLETED`, `CANCELLED`, `UNKNOWN`) |
| `ledger_record_deliverable` | author | `issue`, `spec`, `task`에 산출물(`document`, `commit`, `media`, `scene`, `dataset`, `design`, `artifact`, `other`)을 기록하거나 보완하고, 선택으로 `RESOLVED` 또는 `COMPLETED` 처리합니다(`mark_done`) |
| `ledger_record_case` | author | 이슈 아래에 관찰된 사례를 추가합니다(`MERGED` 이슈는 거절되며 병합 대상을 알려 줍니다) |
| `ledger_link` | author | 이슈, 명세, 작업, 기준을 연결(`issue_task`, `issue_spec`, `spec_task`, `criterion_task`, `criterion_issue`, `criterion_spec` 또는 이슈 `RELATION_KIND` 코드)하거나 연결을 제거합니다 |
| `ledger_correct` | author | 이력 행이 잘못되었음을 표시합니다 |
| `ledger_batch` | author | 프로젝트 버전 변경을 포함한 작성자 작업을 한 트랜잭션에서 최대 200개까지 처리합니다. 배치에 `operationKey` 하나를 줍니다 |
| `ledger_review` | reviewer | 독립 검수(`VERIFIED`, `MET`, `NOT_MET`)와 재오픈을 처리합니다(`evidence_uri` 필수) |
| `ledger_review_correct` | reviewer | 검수 행이 잘못되었음을 표시합니다 |

첨부 도구. 업로드 시작, 번들 확정, 확정 번들 상태 조회와 본문 읽기에는 활성 권한이 있는 세션이 필요합니다. 인증된 `attachments-send` CLI는 파일별 `uploadId`로 청크를 직접 보내고 이어서 전송합니다.

| 도구 | 내용 |
|---|---|
| `artifact_begin` | 1..64개 파일 번들(`files`, 필수 `operationKey`, 선택 `purpose` = `evidence`) 업로드를 시작합니다 |
| `artifact_chunk` | 다음 청크(최대 262144바이트, base64)를 `chunkSha256`과 함께 보냅니다. `uploadId`, `sequence`, `chunkSha256`으로 멱등입니다(`operationKey` 없음) |
| `artifact_finish` | 선언한 파일 크기와 SHA-256을 모두 대조하고 첨부 번들을 봉인합니다. `attachmentId`로 멱등입니다(`operationKey` 없음) |
| `artifact_status` | `uploadId`나 `attachmentId`의 상태, 크기, SHA-256, 만료, 진행률을 조회합니다(경로나 저장 키는 노출하지 않음) |
| `artifact_read` | 봉인된 첨부에서 파일 `seq`의 한 페이지를 복호화·검증해 읽습니다 |

읽기 도구(증명과 부여가 필요하지 않습니다. 1.x의 원시 SQL 도구를 대체하는 정형 조회입니다).

| 도구 | 내용 |
|---|---|
| `ledger_get` | 최대 200개 ID의 상태와 행 버전을 한 번에 조회합니다. `include_details=true`를 지정하면 최대 10개 ID의 이슈·명세·작업·기준 상세도 받습니다 |
| `ledger_issue` | 이슈 하나와 산출물, 작업, 명세, 기준, 사례(병합된 이슈 포함), 관계, `owner_actor_id`, 최근 이력을 보여 줍니다 |
| `ledger_spec` | 명세 하나와 요약, 수용 기준, 상위·하위 명세, 산출물, 작업, 이슈, 기준, 최근 이력을 보여 줍니다 |
| `ledger_task` | 작업 하나의 목적, 성공 조건, 출처, 담당, 산출물과 연결 항목을 조회합니다. 변경 이력은 `ledger_history`를 사용합니다 |
| `ledger_criterion` | 마일스톤 기준 하나와 마일스톤, 목표 라벨, 코드, 기준 문장, 상태 및 연결된 작업·이슈·명세를 보여 줍니다 |
| `ledger_history` | 추가 전용 이력과 영수증을 ID, ID 범위, 대상, 프로젝트, 연산, 작성자, 기간으로 조회합니다. `include_before`를 주면 `before_data`를 더합니다 |
| `ledger_search` | 키워드 또는 `project`와 `project_version`으로 여러 유형의 항목 ID를 찾습니다. 키워드 조건은 `ANY`(기본) 또는 `ALL`입니다 |
| `ledger_issues` | 프로젝트의 이슈를 상태, 심각도, 분류, 주의 종류, 담당, 관련 버전(`project_version`)으로 거릅니다 |
| `ledger_specs` | 명세를 상태, 상위 ID, 우선순위, 담당, 주의 종류, `project_version`으로 거릅니다 |
| `ledger_tasks` | 작업을 상태, 담당, 주의 종류, `project_version`으로 거릅니다 |
| `ledger_criteria` | 출시 기준을 상태, `milestone_name`, `project_version`으로 거릅니다 |
| `ledger_attention` | 조치가 필요한 항목입니다: `OPEN_WITH_DELIVERABLE`, `RESOLVED_WITHOUT_DELIVERABLE`, `OPEN_NO_OWNER`, `SPEC_COMPLETED_NOT_VERIFIED`, `TASK_UNKNOWN`, `DONE_NOT_VERIFIED` |
| `ledger_stats` | 한 프로젝트 또는 전체의 상태별, 심각도별 건수입니다. `bucket`을 `day`로 주면 UTC 일별 신규 건수를 더합니다 |
| `ledger_projects` | 고정 `project` ID 또는 `display_name`에 포함된 문구로 프로젝트를 찾습니다. 응답의 `id`는 고정 ID, `code`는 항목 ID 접두사, `title`은 설명, `display_name`은 현재 표시명입니다. `include_versions=true`로 버전명을 조회합니다 |
| `ledger_codes` | 공통코드입니다. 폐기한 코드는 `include_inactive`를 주면 포함됩니다 |

목록형 읽기 도구는 `max_rows`(1에서 1000, 기본 50)와 `cursor`를 받으며, 응답에 `truncated`와 `next_cursor`가 붙습니다. `ledger_search`의 `created_from`, `created_to`는 이슈, 명세, 작업에 적용됩니다. 기준은 생성 시각이 없으므로 둘 중 하나라도 주면 검색에서 빠집니다(범위와 함께 기준만 검색하면 결과가 없습니다).
1.x의 `execute_sql_reader`와 `search_objects_reader` 도구는 제거되었으므로 위의 읽기 도구를 사용합니다.

모든 MCP 도구에서 `fields`로 `data` 안의 필요한 경로를 최대 16개 지정할 수 있습니다. 일괄 변경 전에
`ledger_get`에 `["items.id","items.state","items.version","not_found"]`를 지정하는 식입니다.
경로는 배열의 각 항목에 적용됩니다. 목록에서 `items`를 선택하면 `truncated`와 `next_cursor`도 받습니다.
`ledger_get`은 `not_found`를 유지하고, 첨부 본문에는 읽은 바이트 구간이 함께 반환됩니다.
클라이언트가 MCP `structuredContent`를 읽을 때는 `structured_only=true`로 중복 텍스트를 생략할 수 있습니다.
같은 사유로 여러 건을 변경할 때는 `ledger_batch`의 `default_reason`을 한 번만 지정하세요. 각 항목에서
`reason`으로 덮어쓸 수 있습니다. 첨부 원문은 필요한 바이트 범위를 지정하고 `["text","size","totalBytes"]`를 선택합니다.

프로젝트의 `issue` 권한으로 `ledger_batch`의 `op=project_version`에서 버전 등록·이름 변경·설명 수정을
할 수 있습니다. 기존 항목을 연결하거나 해제할 때는 유형별로 `link_items` 또는 `unlink_items`를 지정하세요.

```json
{"op":"project_version","project_id":"studio","name":"Review 2","link_items":{"spec":["STUDIO-S-0001"],"task":["STUDIO-T-0001"]}}
```

`create=true`로 버전을 등록하고, `original_name`을 지정해 이름을 바꿉니다. 연결과 해제는 각각 같은 프로젝트의
항목 ID를 최대 200개까지 받습니다. 이슈 연결에는 `issue`, 명세에는 `spec`, 작업과 출시 기준에는 `task` 권한이 필요합니다.
기존 `link_issue_ids`와 `unlink_issue_ids`도 사용할 수 있습니다. 사유는 작업의 `reason` 또는 배치의 `default_reason`으로
지정하세요. 새 항목을 바로 연결할 때는 `op=save`에 `project_version`을 지정합니다.

버전명은 `ledger_projects(project=..., include_versions=true)`에서 조회합니다. 여러 항목 유형을 한 번에 보려면
`ledger_search`에 `project`와 `project_version`을 지정하세요. 유형별 조건이 필요하면 해당 목록 도구를 사용합니다.
ID를 알고 있으면 `ledger_get(include_details=true)`에서 연결된 버전을 함께 확인할 수 있습니다.
장부 항목 두 개 사이의 관계는 `ledger_link`로 관리합니다.

ID를 모르면 `ledger_search`, ID를 알면 `ledger_get`, 조치 대기 목록은 `ledger_attention`, 집계는 `ledger_stats`를 사용합니다.
`ledger_attention`에 `include_context=true`를 지정하면 제목·담당·수정 시각도 함께 반환합니다.
`ledger_whoami(project=...)`는 해당 프로젝트의 권한만 보여 줍니다. `can_verify_login`은 세션 전체 기준이며,
`ledger_review`에서 대상 프로젝트의 권한과 검수 독립성을 검사합니다.

공통코드가 바뀌면 서버가 `notifications/tools/list_changed`를 보내므로, 연결된 클라이언트는 재시작 없이 도구 정의를 다시 읽습니다. 다른 프로세스(관리 웹, `ledger-cli`)가
바꾼 내용은 `ledger.mcp.code-poll-interval` 안에 감지합니다. 새 도구는 서버가 시작될 때 등록됩니다.

`ledger_record_case`는 `issue_id`, `observation`(1자에서 4000자), `reason`을 받고, 선택으로 `evidence`, `source_quote`, `observed_at`(`Z` 또는 오프셋이 붙은 ISO-8601)을 받습니다.
`ledger_batch`에서는 같은 인자를 연산 `{"op": "record_case", ...}`에 넣습니다.

```json
{"issue_id": "WEB-I-0007", "observation": "checkout returns 500 for a gift card",
 "evidence": "request 7f3a, api/checkout.ts:88", "source_quote": "I can't pay with my gift card",
 "observed_at": "2026-11-05T10:00:00Z", "reason": "second report of the same failure"}
```

## 첨부

검수 보고서(또는 그 밖의 근거)는 첨부로 보관합니다. 데이터베이스에 본문 텍스트로 두지 않고, 흔적 없이 고칠 수 있는 로컬 파일로도 두지 않습니다. 기록의 이력 행은 `ledger_transition`, `ledger_review`, `ledger_review_correct`, `ledger_correct`, `ledger_record_deliverable`, `ledger_record_case`와 `ledger_batch`의 같은 연산이 받는 선택 인자 `attachment_id`로 첨부를 가리킵니다. 기록은 첨부가 있고 기록을 쓰는 작성자가 올린 것일 때만 쓰입니다.

- **업로드.** 업로드는 위의 MCP 도구 다섯 개로 합니다. `artifact_begin`(필수 `operationKey`, 순서가 있는 1..64개 파일 목록 `files`), 청크마다 `artifact_chunk`(최대 262144바이트), `artifact_finish`(`attachmentId`), 확인·조회용 `artifact_status`와 `artifact_read`(선택 `seq`, 기본값 `0`)입니다. PC의 로컬 파일을 모델 문맥을 거치지 않고 업로드하려면 `ledger-cli attachments-prepare <file> [--name=<name>]`으로 한 번에 크기와 SHA-256을 측정하고, `ledger-cli attachments-send <file> --upload-id=<id>`로 `/mcp`에 직접 청크를 전송합니다(세션 증명 없이 `uploadId` 권한으로 전송합니다).
- **저장.** 데이터베이스에는 번들·파일 메타데이터와 저장 사본 판본만 둡니다(`upload_staging`, `attachment`, `attachment_file`, `attachment_storage`. 본문 열도 외래 키도 없습니다). 파일 사본은 `ledger.attachments.directory` 아래에 128비트 무작위 이름으로, 확장자와 원본 이름 없이 두며, 청크마다 한 세그먼트로 AES-256-GCM 암호화하고(저장 키와 위치를 함께 인증합니다) 끝나면 읽기 전용으로 바꿉니다. 첨부는 바꾸거나 지우지 않습니다. 데이터베이스의 응용 계정은 첨부에 `SELECT`와 `INSERT`만 가집니다.
- **키.** `setup`이 첫 256비트 키 파일을 `ledger.attachments.key-directory`에 만들고(서비스 계정만 읽습니다) `ledger.attachments.active-key-id`를 정합니다. 교체는 `ledger-cli attachments-key-create`, `active-key-id`를 새 id로 바꾸기, 재시작, 그다음 `ledger-cli attachments-rekey --from=<old id>`로 옛 키의 파일을 다시 쓰는 순서입니다. 옛 키 파일은 현재 파일이나 열린 업로드가 쓰는 동안 남깁니다. 필요한 키 파일이 없으면 시작 점검이 키가 아니라 키 id를 알립니다.
- **오류.** `ARTIFACT_CHUNK_CONFLICT`, `CHUNK_HASH_MISMATCH`, `ARTIFACT_BUSY`(잠시 뒤 다시 보냅니다), `ARTIFACT_INCOMPLETE`, `ARTIFACT_HASH_MISMATCH`(바이트는 남습니다), `ARTIFACT_CORRUPTED`, `ARTIFACT_LIMIT`, `ARTIFACT_UNSUPPORTED_PURPOSE`, `ARTIFACT_EXPIRED`, `ARTIFACT_NOT_FOUND`, `ARTIFACT_OPERATION_CONFLICT`, `ARTIFACT_STORAGE_FULL`(디스크가 너무 찼으니 소유자가 비워야 합니다), `ARTIFACT_STORAGE_IO_ERROR`, `ARTIFACT_STORAGE_UNSAFE`(저장 경로를 이 용도로 쓸 수 없습니다. 폴더 자리에 파일이 있거나, 파일 자리에 폴더가 있거나, 이 파일 시스템이나 이 계정에서는 소유자 전용으로 만들 수 없는 경우입니다), `ARTIFACT_KEY_MISSING`(쓸 수 있는 키가 없습니다. 키 파일이 없거나, 비었거나, 256비트가 아닙니다. 키 파일을 되살립니다). 이 도구들의 오류에는 모두 `details.retryable`이 실립니다. 같은 요청을 나중에 다시 보내 성공할 수 있을 때만 `true`입니다(`ARTIFACT_BUSY`, 그리고 `details.limit`이 `staging-budget`이나 `open-uploads`인 `ARTIFACT_LIMIT`). `ARTIFACT_INCOMPLETE`는 `false`입니다(빠진 청크를 보낸 뒤 finish를 다시 보냅니다).
- **점검.** `ledger-cli attachments-check`는 없거나 손상된 첨부(지우지 않습니다)와 어떤 이력 행도 가리키지 않는 첨부(보존합니다)를 알리고, 행이 없는 파일이나 rekey가 대체한 파일을 `orphan-grace`가 지난 뒤 `orphans/`로 옮깁니다. `orphans/`를 비우는 길은 `--purge-orphans`뿐이며(행이 다시 생긴 파일은 지우지 않습니다) 감사 기록에 남습니다. 관리 웹은 같은 결과를 읽기 전용으로 보여 주고, 기록 화면에 텍스트 첨부는 이스케이프해서 보여 주며 그 밖의 형식은 응용을 거치는 내려받기로 제공합니다.
- **백업.** 첨부 폴더, 키 폴더, 데이터베이스를 함께 백업하고 복원합니다. 복원 뒤 `ledger-cli attachments-check`를 실행하면 없거나 손상된 첨부가 없어야 합니다. 격리된 파일의 행이 다시 생기면 점검이 그 파일을 제자리로 돌려놓습니다.
- **설치.** 설치 프로그램이 첨부 폴더(서비스 계정 소유, 다른 계정에는 닫힘), 비밀 디렉터리 옆의 키 폴더(`attachment-keys`), 설정을 만듭니다. 1.x의 근거는 기록된 URI와 해시 그대로 남으며 이전은 아무것도 가져오지 않습니다.
