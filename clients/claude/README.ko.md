# Claude Code용 pg-ledger-mcp 플러그인

[English](README.md)

이 디렉터리는 Claude Code 플러그인입니다. Claude Code 세션이 호출하는 장부 쓰기 도구의 작성자를 증명합니다. 세 클라이언트(Claude Code, Codex, Antigravity)는 같은 실행 jar인 `ledger-client-hook.jar`를 사용하며, 첫 번째 인자로 클라이언트를 구분합니다.

## 동작

`hooks/hooks.json`의 `PreToolUse` 훅은 장부 쓰기 도구를 호출하기 전에 `java -jar "${CLAUDE_PLUGIN_ROOT}/lib/ledger-client-hook.jar" claude`를 실행합니다. 훅은 쓰기 도구 10종(`save`, `transition`, `record_fix`, `record_case`, `link`, `correct`, `batch`, `review`, `review_correct`, `whoami`)만 처리하며, 도구 이름은 `mcp__<server>__ledger_<op>` 또는 `mcp__<server>__<server>_ledger_<op>`입니다. 두 번째 형식은 게이트웨이가 상위 서버의 이름을 도구 앞에 붙이는 경우이며, 접두어는 서버 이름과 정확히 같아야 합니다. 읽기 도구와 그 밖의 모든 도구는 변경 없이 통과합니다.

쓰기 호출에는 예약 인자 `_hostAttestation`을 추가하고, 바뀐 입력을 `updatedInput`으로 돌려줍니다. 값은 작성자 `claude:<session_id>`의 Ed25519 세션 키로, 호출 대상 MCP 서버에 대해 만든 서명입니다. 서명은 도구, 요청 ID, 모든 인자의 정규형(RFC 8785)에 대한 SHA-256 다이제스트, 시각을 포함합니다. 호출에 `request_id`가 없으면 훅이 하나를 생성합니다. 훅은 권한 결정을 내리지 않으므로 Claude Code의 권한 정책이 그대로 적용됩니다. 모델이 `_hostAttestation`을 직접 넣거나 호스트의 `session_id`가 올바르지 않으면 호출을 거부하며, JSON이 잘못되었으면 종료 코드 2로 호출을 막습니다.

## 요구 사항

훅 명령은 `PATH`에서 `java`를 찾으므로, Claude Code를 실행하는 호스트의 `PATH`에 JDK 25 이상이 있어야 합니다. `java`가 없으면 명령이 실패하고, 더 낮은 JDK에서는 JVM이 `UnsupportedClassVersionError`로 jar를 거부합니다. 두 경우 모두 `_hostAttestation`이 추가되지 않으며 서버는 쓰기를 `HOST_ATTESTATION_REQUIRED`로 거절합니다. 따라서 훅이 실패해도 작성자가 증명되지 않은 쓰기가 저장되는 일은 없습니다.

## 설치

jar는 저장소에 커밋하지 않습니다. `./gradlew :ledger-client-hook:assemble`을 실행하면 `ledger-client-hook/build/client-plugins/claude/`에 플러그인 디렉터리가 만들어지며, jar는 `lib/ledger-client-hook.jar`에 들어 있습니다. 이 디렉터리를 Claude Code의 플러그인 위치에 복사합니다.

## 세션 키 등록

1. 어떤 작성자가 어떤 서버에 처음 쓰기를 호출하면 그 작성자와 서버의 키 쌍이 만들어집니다. 키는 소유자 전용 폴더인 `~/.pg-ledger-mcp/session-keys`에 보관하며, `LEDGER_HOOK_KEY_DIR`에 절대 경로를 주어 바꿀 수 있습니다. Windows에서는 폴더에 소유자만 허용하는 ACL을 만들고, 다른 주체에 접근을 허용한 폴더나 파일은 거부합니다. 상승된 권한으로 실행한 훅도 거부합니다. 이 경우 키 폴더의 소유자가 달라지기 때문입니다.
2. `java -jar lib/ledger-client-hook.jar session-key <server> <author>`는 서버, 작성자, 공개 키, 지문을 출력합니다. `<server>`는 호스트가 도구 이름에 보여 주는 MCP 서버의 이름이며, 예를 들어 `mcp__ledger__ledger_save`의 서버는 `ledger`입니다.
3. 소유자는 지문을 세션이 보여 준 지문과 대조한 뒤, `ledger-cli`의 `key-register`로 또는 관리 웹의 권한 부여 화면에서 공개 키를 등록합니다. 그 뒤에야 작성자의 권한 부여가 동작합니다.

키는 서버 하나와 작성자 하나에 속합니다. 시험 서버나 이름이 비슷한 서버를 위해 만든 서명은 운영 서버에서 검증되지 않으므로 서버마다 따로 등록합니다. 서버는 권한 부여를 보기 전에 서명, 도구, 요청 ID, 인자, 시각을 검증합니다.

## 한계

같은 운영체제 사용자의 다른 프로세스는 키 파일을 읽을 수 있으므로, 한 사용자 안의 격리에는 별도 계정이 필요합니다.

## 참고

- [Claude Code PreToolUse 계약](https://code.claude.com/docs/en/hooks#pretooluse)
- [메인 README의 클라이언트 훅](../../README.ko.md)
