# Codex 호스트 작성자 훅 후보

이 디렉터리는 실행 jar `ledger-client-hook.jar`를 `java -jar … codex`로 호출하는 플러그인 후보다. 세 클라이언트(Claude, Codex, Antigravity)가 같은 jar를 쓰며 첫 인자로 구분한다. 실행에는 JDK 25 이상이 필요하다(더 낮은 JDK는 클래스 파일 버전 69.0 오류로 즉시 실패한다). 실제 전역 설치나 운영 검증을 뜻하지 않는다. MCP 연결 설정이나 인증 정보는 포함하지 않는다.

`plugin.json`의 `extensions.com.openai.hooks`가 `hooks/hooks.json`을 가리킨다. 명령은 플러그인 루트 환경변수로 `lib/ledger-client-hook.jar`를 찾는다(`java -jar "${PLUGIN_ROOT}/lib/ledger-client-hook.jar" codex`). Windows 명령은 `cmd.exe`를 명시한다. jar는 저장소에 커밋하지 않는다. `./gradlew :ledger-client-hook:assemble`이 `ledger-client-hook/build/client-plugins/codex/`에 jar가 든 플러그인 디렉터리를 만들며, 설치는 이 디렉터리를 호스트의 플러그인 위치에 복사하는 것이다. 호스트가 플러그인을 로드한 뒤 `/hooks`에서 정확한 정의를 검토·신뢰해야 한다. 프로젝트 훅으로 배치할 때는 `<repo>/.codex/hooks.json`에 해당 구성을 병합하고 jar 명령을 실제 절대경로로 바꾼다. 기존 훅을 덮어쓰거나 중복 설치하지 않는다.

대상은 `mcp__`로 시작하고 `_ledger_` 뒤에 `save`, `transition`, `record_fix`, `record_case`, `link`, `correct`, `batch`, `review`, `review_correct`, `whoami`가 붙어 끝나는 10종이다. `mcp__server__ledger_save`와 `mcp__codex_apps__app_ledger_ledger_save` 같은 네임스페이스를 포함한다. 모델 인자의 own `_hostAttestation` 키는 값과 무관하게 거부한다. 없으면 호스트 `session_id`를 그대로 `codex:<id>`로 넣는다. ID는 기존 DB의 세션 부분 형식 `[A-Za-z0-9._-]{1,128}`을 따라 검사하며 누락·잘못된 형식은 명시적으로 거부한다. 구형 커넥터의 필수 인자 검사와 호환되도록 기존 `author_id`와 다른 인자는 보존하고, 없는 `author_id`를 새로 만들지 않는다. 서버는 이 구형 값을 무시하고 호스트가 넣은 `_hostAttestation`으로만 작성자를 결정한다. 대상 밖 호출에는 출력 없이 종료한다. 잘못된 JSON이나 대상의 비객체 인자는 거부 응답을 반환한다.

Codex 공식 계약은 하위 에이전트 훅에 부모 `session_id`를 제공한다. 이 후보는 다른 식별자를 찾거나 자식별 권한·차단 계층을 만들지 않는다. `updatedInput`은 `permissionDecision: allow`와 함께 반환한다. 훅의 허용·거부 JSON 응답은 정상 종료 0이며, 프로세스 실패나 일부 우회 경로까지 서버 신원 강제를 보장하는 구성은 아니다.

실행 환경 요구: 훅 명령은 `java`를 `PATH`에서 찾는다. 이 클라이언트를 실행하는 호스트에 JDK 25 이상이 `PATH`에 있어야 한다. `java`가 없으면 명령 실행이 실패하고, 25 미만이면 JVM이 `UnsupportedClassVersionError`(클래스 파일 버전 69.0)로 jar를 거부한다. 두 경우 모두 훅은 `_hostAttestation`을 넣지 못한다.

실패 시 동작: 훅이 실패하거나 누락돼 `_hostAttestation`이 없는 쓰기 호출은 서버가 `HOST_ATTESTATION_REQUIRED`로 거절한다. 따라서 훅 실패는 작성자 신원 없는 쓰기가 저장되는 방향이 아니라 닫힌 쪽(거절)으로 끝난다.

공식 근거: [훅 검색 경로·신뢰·입출력](https://learn.chatgpt.com/docs/hooks), [플러그인 manifest와 훅 경로](https://developers.openai.com/plugins/build/plugins). 실제 호스트 전달, Windows 실행, 운영 적용 전 보안·권한 감사는 별도 근거가 필요하다.
