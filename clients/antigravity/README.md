# Antigravity 호스트 작성자 훅 후보

이 디렉터리는 Node.js 표준 라이브러리만 쓰는 플러그인 후보다. 실제 전역 설치나 운영 검증을 뜻하지 않는다. MCP 연결 설정이나 인증 정보는 포함하지 않는다.

네이티브 플러그인 루트에 `plugin.json`과 `hooks.json`을 둔다. 플러그인 훅 명령의 작업 디렉터리는 플러그인 루트이므로 `node ./scripts/ledger-host-attestation.mjs`를 쓴다. 프로젝트 훅으로 배치할 때는 `<workspace>/.agents/hooks.json`의 명명된 훅 그룹에 병합하고 명령을 실제 스크립트 절대경로로 바꾼다. 전역 경로는 `~/.gemini/config/hooks.json`이며 기존 구성을 덮어쓰거나 중복 설치하지 않는다.

`mcp:.*` matcher 아래에서 `toolCall.name`의 마지막 `ledger_` 도구명을 검사한다. `mcp:ledger_save`, `mcp:<server>:ledger_save`, `mcp:<server>/ledger_save`, `mcp:<server>_ledger_save` 형태를 대상으로 한다. 대상 접미사는 `save`, `transition`, `record_fix`, `record_case`, `link`, `correct`, `batch`, `review`, `review_correct`, `whoami` 10종이다. 실제 호스트의 MCP 이름·인자 포장은 네이티브 관측이 필요하다.

모델 인자의 own `_hostAttestation` 키는 값과 무관하게 거부한다. 없으면 호스트 `conversationId`를 그대로 `gemini:<id>`로 넣는다. ID는 기존 DB의 세션 부분 형식 `[A-Za-z0-9._-]{1,128}`을 따라 검사하며 누락·잘못된 형식은 명시적으로 거부한다. `overwrite`에는 이 키 하나만 반환한다. 얕은 병합으로 다른 인자와 legacy `author_id`가 보존되며 서버는 `author_id`를 신원 근거로 쓰지 않아야 한다. 대상 밖 호출에는 출력 없이 종료한다. 잘못된 JSON이나 대상의 비객체 인자는 거부 응답을 반환한다.

`decision: allow`만 사용하며 `auto_approve`나 `permissionOverrides`는 넣지 않는다. 기존 호스트 권한 정책이 계속 적용된다. 하위 에이전트에 부모 `conversationId`가 제공된다는 보장은 미확인이다. 이를 추측하여 보정하거나 자식별 식별·권한·차단 계층을 만들지 않는다. 허용·거부 JSON은 정상 종료 0이다. Antigravity 문서상 비정상 종료는 무시되므로 이 후보만으로 프로세스 실패 시 차단을 보장하지 않는다.

공식 근거: [PreToolUse 입력·출력과 설정 경로](https://antigravity.google/docs/hooks/#pretooluse), [플러그인 배치·명령 cwd·실패 처리](https://antigravity.google/docs/build-plugins/#lifecycle-hooks). 실제 호스트 전달과 서버 unknown-field 수신 허용, 운영 적용 전 보안·권한 감사는 별도 근거가 필요하다.
