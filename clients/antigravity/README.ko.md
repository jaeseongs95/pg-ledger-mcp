# Antigravity용 pg-ledger-mcp 플러그인

[English](README.md)

이 디렉터리는 Antigravity 플러그인입니다. Antigravity 대화가 호출하는 장부 쓰기 도구의 작성자를 증명합니다. 세 클라이언트(Claude Code, Codex, Antigravity)는 같은 실행 jar인 `ledger-client-hook.jar`를 사용하며, 첫 번째 인자로 클라이언트를 구분합니다. 이 플러그인에는 MCP 연결 설정과 인증 정보가 들어 있지 않습니다.

## 동작

`plugin.json`과 `hooks.json`은 플러그인 루트에 있습니다. 플러그인 훅의 명령은 플러그인 루트에서 실행되므로, `hooks.json`은 도구를 호출하기 전에 `java -jar "./lib/ledger-client-hook.jar" antigravity`를 실행합니다. matcher는 `call_mcp_tool`과 쓰기 도구의 직접 MCP 이름(`mcp:...ledger_<op>`)을 고르고, 훅은 도구 이름을 다시 검사합니다. 훅은 쓰기 도구 10종(`save`, `transition`, `record_fix`, `record_case`, `link`, `correct`, `batch`, `review`, `review_correct`, `whoami`)만 처리하며, 서버 이름이 드러나는 이름에만 서명합니다.

- 직접 호출: `mcp:<server><:|/|_>[<server>_]ledger_<op>`
- `call_mcp_tool` 호출: `ServerName`이 서버 이름(`A-Za-z0-9._-` 1~100자)이고 `ToolName`이 `ledger_<op>` 또는 `<ServerName>_ledger_<op>`인 경우

`mcp:ledger_save`처럼 서버 이름이 없는 이름에는 서명하지 않습니다. 그 밖의 도구와 읽기 도구는 변경 없이 통과합니다.

쓰기 호출에는 예약 인자 `_hostAttestation`을 추가합니다. 값은 작성자 `gemini:<conversationId>`의 Ed25519 세션 키로, 호출 대상 MCP 서버에 대해 만든 서명입니다. 서명은 도구, 요청 ID, 모든 인자의 정규형(RFC 8785)에 대한 SHA-256 다이제스트, 시각을 포함합니다. 호출에 `request_id`가 없으면 훅이 하나를 생성합니다. 직접 호출에는 `overwrite`로 응답하며 Antigravity가 이를 도구의 인자에 병합합니다. `call_mcp_tool` 호출에는 교정한 `Arguments`만 돌려줍니다. `Arguments`는 객체이거나 JSON 객체를 담은 문자열이며 원래의 타입을 유지합니다. 다른 인자는 그대로 두며, 이전 커넥터의 `author_id`가 있어도 사용하거나 복사하지 않습니다.

훅은 `allow`로만 응답하고 `auto_approve`나 `permissionOverrides`는 설정하지 않으므로 Antigravity의 권한 정책이 그대로 적용됩니다. 모델이 `_hostAttestation`을 직접 넣거나 `conversationId`가 올바르지 않으면 호출을 거부합니다. 잘못된 JSON도 종료 코드 0과 함께 명시적으로 거부합니다. Antigravity가 하위 에이전트에 부모의 `conversationId`를 제공하는지는 확인되지 않았습니다. 훅은 이를 추측하지 않으며, 하위 에이전트를 위한 별도의 식별·권한 계층도 두지 않습니다.

## 요구 사항

훅 명령은 `PATH`에서 `java`를 찾으므로, Antigravity를 실행하는 호스트의 `PATH`에 JDK 25 이상이 있어야 합니다. `java`가 없으면 명령이 실패하고, 더 낮은 JDK에서는 JVM이 `UnsupportedClassVersionError`로 jar를 거부합니다. 두 경우 모두 `_hostAttestation`이 추가되지 않으며 서버는 쓰기를 `HOST_ATTESTATION_REQUIRED`로 거절합니다. 따라서 훅이 실패해도 작성자가 증명되지 않은 쓰기가 저장되는 일은 없습니다. Antigravity는 오류로 종료한 훅 프로세스를 무시하므로, 서버의 이 거절이 유일한 방어선입니다.

## 설치

jar는 저장소에 커밋하지 않습니다. `./gradlew :ledger-client-hook:assemble`을 실행하면 `ledger-client-hook/build/client-plugins/antigravity/`에 플러그인 디렉터리가 만들어지며, jar는 `lib/ledger-client-hook.jar`에 들어 있습니다. 이 디렉터리를 Antigravity의 플러그인 위치에 복사합니다.

프로젝트 훅으로 쓰려면 `<workspace>/.agents/hooks.json`의 이름 붙은 훅 그룹에 병합하고 jar의 경로를 절대 경로로 바꿉니다. 전역 파일은 `~/.gemini/config/hooks.json`입니다. 기존 설정을 덮어쓰거나 훅을 두 번 설치하지 않습니다.

## 세션 키 등록

1. 어떤 작성자가 어떤 서버에 처음 쓰기를 호출하면 그 작성자와 서버의 키 쌍이 만들어집니다. 키는 소유자 전용 폴더인 `~/.pg-ledger-mcp/session-keys`에 보관하며, `LEDGER_HOOK_KEY_DIR`에 절대 경로를 주어 바꿀 수 있습니다. Windows에서는 폴더에 소유자만 허용하는 ACL을 만들고, 다른 주체에 접근을 허용한 폴더나 파일은 거부합니다. 상승된 권한으로 실행한 훅도 거부합니다. 이 경우 키 폴더의 소유자가 달라지기 때문입니다.
2. `java -jar lib/ledger-client-hook.jar session-key <server> <author>`는 서버, 작성자, 공개 키, 지문을 출력합니다. `<server>`는 Antigravity가 도구 이름이나 `ServerName`에 보여 주는 MCP 서버의 이름입니다.
3. 소유자는 지문을 세션이 보여 준 지문과 대조한 뒤, `ledger-cli`의 `key-register`로 또는 관리 웹의 권한 부여 화면에서 공개 키를 등록합니다. 그 뒤에야 작성자의 권한 부여가 동작합니다.

키는 서버 하나와 작성자 하나에 속합니다. 시험 서버나 이름이 비슷한 서버를 위해 만든 서명은 운영 서버에서 검증되지 않으므로 서버마다 따로 등록합니다. 서버는 권한 부여를 보기 전에 서명, 도구, 요청 ID, 인자, 시각을 검증합니다.

## 한계

같은 운영체제 사용자의 다른 프로세스는 키 파일을 읽을 수 있으므로, 한 사용자 안의 격리에는 별도 계정이 필요합니다. `Arguments`의 실제 형태와 교정한 인자를 서버가 받는지는 네이티브 클라이언트에서 따로 확인해야 하며, 운영 적용 전의 보안 검토와 권한 검토도 마찬가지입니다.

## 참고

- [PreToolUse 입력·출력과 설정 경로](https://antigravity.google/docs/hooks/#pretooluse)
- [플러그인 배치, 명령 디렉터리, 실패 처리](https://antigravity.google/docs/build-plugins/#lifecycle-hooks)
- [메인 README의 클라이언트 훅](../../README.ko.md)
