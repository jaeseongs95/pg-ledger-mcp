# Antigravity 호스트 작성자 훅 후보

이 디렉터리는 실행 jar `ledger-client-hook.jar`를 `java -jar … antigravity`로 호출하는 플러그인 후보다. 세 클라이언트(Claude, Codex, Antigravity)가 같은 jar를 쓰며 첫 인자로 구분한다. 실행에는 JDK 25 이상이 필요하다(더 낮은 JDK는 클래스 파일 버전 69.0 오류로 즉시 실패한다). 실제 전역 설치나 운영 검증을 뜻하지 않는다. MCP 연결 설정이나 인증 정보는 포함하지 않는다.

네이티브 플러그인 루트에 `plugin.json`과 `hooks.json`을 둔다. 플러그인 훅 명령의 작업 디렉터리는 플러그인 루트이므로 `java -jar "./lib/ledger-client-hook.jar" antigravity`를 쓴다. jar는 저장소에 커밋하지 않는다. `./gradlew :ledger-client-hook:assemble`이 `ledger-client-hook/build/client-plugins/antigravity/`에 jar가 든 플러그인 디렉터리를 만들며, 설치는 이 디렉터리를 호스트의 플러그인 위치에 복사하는 것이다. 프로젝트 훅으로 배치할 때는 `<workspace>/.agents/hooks.json`의 명명된 훅 그룹에 병합하고 명령을 실제 jar 절대경로로 바꾼다. 전역 경로는 `~/.gemini/config/hooks.json`이며 기존 구성을 덮어쓰거나 중복 설치하지 않는다.

matcher는 `call_mcp_tool`과 쓰기 도구 10종의 직접 MCP 이름(`mcp:...ledger_<op>`)만 고른다. 훅은 마지막 `ledger_` 도구명을 다시 검사한다. 대상 접미사는 `save`, `transition`, `record_fix`, `record_case`, `link`, `correct`, `batch`, `review`, `review_correct`, `whoami` 10종이다. 직접 MCP 이름 형태와 CLI의 `call_mcp_tool` 포장을 지원한다. 후자는 비어 있지 않은 `toolCall.args.ServerName`과 대상 접미사 `ToolName`일 때 `Arguments` 내부만 교정한다. `Arguments`는 객체 또는 JSON 객체 문자열이며 원래 타입을 유지한다. 일반 도구·조회는 통과한다. 실제 CLI 관측에서 wrapper 이름과 세 키는 확인됐고 `Arguments`의 실제 타입·교정 후 수신은 별도 네이티브 확인이 필요하다.

서명은 서버 이름이 드러나는 이름만 대상으로 한다: 직접 호출은 `mcp:<서버><:|/|_>[<서버>_]ledger_<작업>`, `call_mcp_tool`은 `ServerName`과 `ledger_<작업>` 또는 `<ServerName>_ledger_<작업>`인 `ToolName`이다(서버 이름 1~100자 `A-Za-z0-9._-`). 서버 이름이 없는 `mcp:ledger_save`는 서명하지 않는다. 키는 (서버, 작성자)마다 따로이고 소유자는 서버마다 `session-key <서버> <작성자>`의 공개 키를 따로 등록한다. 서명에는 호출 인자 전체의 다이제스트도 들어간다.

모델 인자의 own `_hostAttestation` 키는 값과 무관하게 거부한다. 없으면 호스트 `conversationId`를 그대로 `gemini:<id>`로 넣는다. ID는 기존 DB의 세션 부분 형식 `[A-Za-z0-9._-]{1,128}`을 따라 검사하며 누락·잘못된 형식은 명시적으로 거부한다. 직접 MCP 호출에서는 `overwrite`에 이 키 하나만 반환하고 wrapper에서는 수정한 `Arguments`만 반환한다. 얕은 병합으로 다른 인자와 legacy `author_id`가 보존되며 서버는 `author_id`를 신원 근거로 쓰지 않아야 한다. 대상 밖 호출에는 출력 없이 종료한다. 잘못된 JSON이나 대상의 비객체 인자는 거부 응답을 반환한다.

`decision: allow`만 사용하며 `auto_approve`나 `permissionOverrides`는 넣지 않는다. 기존 호스트 권한 정책이 계속 적용된다. 하위 에이전트에 부모 `conversationId`가 제공된다는 보장은 미확인이다. 이를 추측하여 보정하거나 자식별 식별·권한·차단 계층을 만들지 않는다. 허용·거부 JSON은 정상 종료 0이다. Antigravity 문서상 비정상 종료는 무시되므로 이 후보만으로 프로세스 실패 시 차단을 보장하지 않는다.

실행 환경 요구: 훅 명령은 `java`를 `PATH`에서 찾는다. 이 클라이언트를 실행하는 호스트에 JDK 25 이상이 `PATH`에 있어야 한다. `java`가 없으면 명령 실행이 실패하고, 25 미만이면 JVM이 `UnsupportedClassVersionError`(클래스 파일 버전 69.0)로 jar를 거부한다. 두 경우 모두 훅은 `_hostAttestation`을 넣지 못한다.

실패 시 동작: 훅이 실패하거나 누락돼 `_hostAttestation`이 없는 쓰기 호출은 서버가 `HOST_ATTESTATION_REQUIRED`로 거절한다. 따라서 훅 실패는 작성자 신원 없는 쓰기가 저장되는 방향이 아니라 닫힌 쪽(거절)으로 끝난다. Antigravity는 비정상 종료를 무시하므로(위 설명) 이 서버 쪽 거절이 유일한 방어선이다.

공식 근거: [PreToolUse 입력·출력과 설정 경로](https://antigravity.google/docs/hooks/#pretooluse), [플러그인 배치·명령 cwd·실패 처리](https://antigravity.google/docs/build-plugins/#lifecycle-hooks). 실제 호스트 전달과 서버 unknown-field 수신 허용, 운영 적용 전 보안·권한 감사는 별도 근거가 필요하다.
