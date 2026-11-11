# Claude Code 호스트 작성자 훅

이 디렉터리는 Claude Code 플러그인이다. `hooks/hooks.json`의 PreToolUse 훅이 실행 jar `ledger-client-hook.jar`를 `java -jar "${CLAUDE_PLUGIN_ROOT}/lib/ledger-client-hook.jar" claude`로 호출한다. 세 클라이언트(Claude, Codex, Antigravity)가 같은 jar를 쓰며 첫 인자로 구분한다. Claude 전용 훅 형식(`http`, `mcp_tool`)은 쓰지 않고 모든 클라이언트가 지원하는 `command` 훅만 쓴다.

jar는 저장소에 커밋하지 않는다. `./gradlew :ledger-client-hook:assemble`이 `ledger-client-hook/build/client-plugins/claude/`에 jar가 든 플러그인 디렉터리를 만들며, 설치는 이 디렉터리를 호스트의 플러그인 위치에 복사하는 것이다.

쓰기 도구 10종(`save`, `transition`, `record_fix`, `record_case`, `link`, `correct`, `batch`, `review`, `review_correct`, `whoami`)에만 호스트 `session_id`를 `claude:<id>`로 `_hostAttestation`에 넣고 `updatedInput`으로 돌려준다. 권한 결정(`permissionDecision`)은 넣지 않아 호스트의 권한 정책이 그대로 적용된다. 모델이 직접 넣은 `_hostAttestation`과 형식에 맞지 않는 `session_id`는 거부한다. 잘못된 JSON이면 종료 코드 2로 호출을 막는다.

실행 환경 요구: 훅 명령은 `java`를 `PATH`에서 찾는다. 이 클라이언트를 실행하는 호스트에 JDK 25 이상이 `PATH`에 있어야 한다. `java`가 없으면 명령 실행이 실패하고, 25 미만이면 JVM이 `UnsupportedClassVersionError`(클래스 파일 버전 69.0)로 jar를 거부한다. 두 경우 모두 훅은 `_hostAttestation`을 넣지 못한다.

실패 시 동작: 훅이 실패하거나 누락돼 `_hostAttestation`이 없는 쓰기 호출은 서버가 `HOST_ATTESTATION_REQUIRED`로 거절한다. 따라서 훅 실패는 작성자 신원 없는 쓰기가 저장되는 방향이 아니라 닫힌 쪽(거절)으로 끝난다.

공식 근거: [PreToolUse 계약](https://code.claude.com/docs/en/hooks#pretooluse).
