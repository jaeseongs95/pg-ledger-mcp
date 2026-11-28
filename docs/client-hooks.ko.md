# 에이전트 연동 가이드

[English](client-hooks.md) · [가이드 목차](guide.ko.md)

## MCP 클라이언트 설정

호스트의 MCP 설정에서 인스턴스의 `/mcp` 주소를 Streamable HTTP 서버로 등록합니다. 같은 호스트에서는 기본 주소가 `http://127.0.0.1:8765/mcp`입니다. 인스턴스의 `ledger.mcp.token`을 Bearer 인증 정보로 사용합니다. 원격 인스턴스에는 운영자가 설정한 HTTPS 주소를 사용합니다.

## 훅 설치와 갱신

에이전트가 실행되는 호스트에서 Java 25를 `PATH`에 두고 설치기를 실행합니다. Linux에서는 `sh deploy/install-hook.sh --hosts=codex,claude,antigravity`, Windows에서는 `deploy\install-hook.cmd --hosts=codex,claude,antigravity`를 실행합니다. 목록에서 실제로 쓰는 호스트만 지정합니다. `--version=vX.Y.Z`로 특정 릴리스를 지정할 수 있습니다.

| 호스트 | 훅 설정과 세션 식별자 |
|---|---|
| [Codex](../clients/codex/README.ko.md) | `/hooks`에서 설치된 훅을 검토하고 신뢰합니다. 권한 부여에 `codex:<session_id>`를 사용합니다. |
| [Claude Code](../clients/claude/README.ko.md) | Claude Code의 `PreToolUse` 설정에 훅을 불러옵니다. 권한 부여에 `claude:<session_id>`를 사용합니다. |
| [Antigravity](../clients/antigravity/README.ko.md) | Antigravity 훅을 불러옵니다. 설치기에는 `antigravity`, 권한 부여에는 `gemini:<conversationId>`를 사용합니다. |

플러그인과 수동 설치 절차는 호스트별 문서에 있습니다. 수동으로 설치할 때는 `ledger-client-hook.jar`와 `ledger-hook-resident.jar`를 같은 폴더에 둡니다.

## 연결 확인

1. 에이전트의 새 세션에서 MCP 도구 목록을 확인합니다. 연결되지 않으면 엔드포인트와 Bearer 인증 정보를 점검합니다.
2. 호스트에서 `ledger_whoami`를 호출해 응답의 작성자가 해당 세션인지 확인합니다. `HOST_ATTESTATION_REQUIRED`이면 호출에 호스트 증명이 누락된 상태입니다. 훅 로딩과 신뢰 설정, Java, JAR 두 개를 확인합니다.
3. **세션 권한**에서 해당 작성자에게 프로젝트에 필요한 권한을 부여합니다. `ledger_whoami`를 다시 호출해 유효 권한을 확인합니다. 쓰기가 `SESSION_NOT_GRANTED`로 거절되면 프로젝트, 권한, 만료를 확인합니다. 입력과 권한 계약은 [MCP 레퍼런스](mcp.ko.md)를 참조합니다.

## 클라이언트 훅

훅은 플러그인 폴더에 나란히 있는 두 프로그램입니다. 호스트는 **가벼운 클라이언트** `ledger-client-hook.jar`를 실행하며, 이 jar가
모든 클라이언트를 처리하고 첫 인자로 클라이언트를 고릅니다: `java -jar ledger-client-hook.jar claude`, `codex`, `antigravity`.
클라이언트는 `java.base`만 불러오며(Spring, Jackson, 로깅 라이브러리 없음) 호출마다 호스트의 입력을 루프백으로 **상주 프로그램**
`ledger-hook-resident.jar`(Spring Boot 애플리케이션)에 보내고 그 답을 출력합니다. 호스트가 보는 것은 바뀌지 않았습니다. 표준 입력은
호스트 JSON이고, 표준 출력에는 답만 나가며(JVM 줄도 로그 줄도 없음), 종료 코드와 실패의 의미도 같습니다. 플러그인 디렉터리는
`clients/claude`, `clients/codex`, `clients/antigravity`에 있습니다(각 README 참조). 안내형 설치나 업그레이드는 `deploy/install-hook.sh`(Linux) 또는 `deploy\install-hook.cmd`(Windows)로 실행할 수 있으며, 두 훅 JAR를 내려받아 `SHA256SUMS`를 검증하고 백업을 남기며 Codex·Claude·Antigravity 설정을 안전하게 병합한 뒤 스모크 테스트까지 수행합니다. 훅에는 클라이언트 호스트의 `PATH`에 Java 25
런타임이 필요하며, 훅이 없거나 실패하면 쓰기는 `HOST_ATTESTATION_REQUIRED`로 거절됩니다.

훅은 쓰기 도구 13종과 첨부 도구 5종에만 각인하며, 도구 이름은 `mcp__<server>__ledger_<op>` 또는 `mcp__<server>__<server>_ledger_<op>`입니다
(Codex Apps는 `mcp__codex_apps__app_<app>_ledger_<op>`). `_hostAttestation`의 값은 `<vendor>:<session-id>`(예: `claude:1f2e3d4c` 또는 `codex:9a8b7c6d`)입니다.
모델이 `_hostAttestation`을 직접 넣으면 훅이 호출을 거부합니다(`RESERVED_FIELD`).

- 상주 프로그램은 첫 호출이 띄우며 설치 단계가 따로 없습니다. 주소 파일이 없거나, 이미 사라진 프로세스를 가리키거나(프로세스 번호와 시작
  시각이 모두 맞아야 하므로 재사용된 번호는 그 프로그램으로 보지 않습니다) 응답하지 않으면 클라이언트가 프로그램의 복사본으로 `java -jar`를
  실행하며, 호출 여러 개가 동시에 와도 하나만 뜹니다(상태 폴더의 잠금이 하나, 프로그램 자신의 잠금이 또 하나). 첫 호출은 약 1초, 이후
  호출은 약 0.1초 걸립니다(측정은 릴리스 노트). 프로그램은 호출이 끝난 뒤에도 남아 있다가 15분 동안 호출이 없으면 스스로 끝납니다
  (`LEDGER_HOOK_RESIDENT_IDLE_TIMEOUT`, `PT30M` 같은 ISO-8601 기간). `java -jar ledger-client-hook.jar stop`으로도 끝낼 수 있습니다.
- 상태 폴더 `~/.veridger/resident/<id>/`에는 주소 파일(포트, 프로세스 번호와 시작 시각, 판,
  시작할 때마다 새로 만드는 비밀 값), 잠금 파일 둘, 프로그램의 출력이 담긴 `resident.log`(비밀 값은 절대 쓰지 않음)가 있습니다. 프로그램은
  시스템이 고른 포트에서 `127.0.0.1`로만 연결을 받습니다. 모든 요청은 주소 파일의 비밀 값을 가져야 하고, 값은 상수 시간으로 비교하며
  없으면 401로 거절하므로 같은 PC의 다른 계정은 프로그램을 부를 수 없습니다. 폴더와 파일은 소유자 전용입니다(POSIX 권한, 또는 파일
  시스템이 지원하는 경우 소유자만 허용하는 ACL).
- 프로그램은 `~/.veridger/resident-runtime/<build>/`의 복사본에서 실행되므로 실행 중인 프로그램이 플러그인 폴더의 jar를 잠그지 않습니다.
  플러그인은 언제든 갱신하거나 삭제할 수 있습니다. 프로그램 파일의 판이 다른 클라이언트(갱신된 플러그인)는 실행 중인 프로그램에 끝내 달라고
  요청하고, 끝나지 않으면 끝낸 뒤 새 프로그램을 띄웁니다.
- 프로그램에 닿지 못하거나 띄우지 못하면 호출은 답할 수 없는 호출이 늘 그랬듯 실패합니다. 종료 코드 2와 표준 오류의
  `ledger: the hook program is not available`(뒤에 이유)이며, 서버는 그 쓰기를 `HOST_ATTESTATION_REQUIRED`로 거절합니다. 상태 폴더의
  `resident.log`를 읽고, `ledger-client-hook.jar`와 `ledger-hook-resident.jar`가 같은 폴더에 있는지, `java`가 JDK 25 이상인지 확인합니다.
  `java -jar ledger-client-hook.jar stop`은 이상하게 동작하는 프로그램을 끝내며, 다음 호출이 새 프로그램을 띄웁니다. 프로그램이 실행 중이
  아닐 때는 `resident`와 `resident-runtime` 폴더를 지워도 됩니다.
- 서버는 세션의 유효한 부여(`session_grant`)를 확인하기 전에 `_hostAttestation`의 작성자 형식(`HOST_ATTESTATION_REQUIRED`, `AUTHOR_ID_FORMAT`)을 검증합니다.
- 한계: 네트워크 수준의 인증은 `ledger.mcp.token`(및 `Host`와 `Origin` 허용 목록)에 의존하며, 클라이언트 훅은 로컬에서 모델이 `_hostAttestation`을 위조하지 못하도록 막습니다. `ledger.mcp.token`은 신뢰하는 소유자 호스트에만 보관하십시오.
