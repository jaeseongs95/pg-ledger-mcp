# 아키텍처

[English](architecture.md) · [가이드 목차](guide.ko.md)

Veridger는 MCP 인터페이스와 관리자 콘솔을 별도 서비스로 실행하고, 두 서비스는 애플리케이션과 데이터베이스 계층을 공유합니다.
MCP 인터페이스는 세션 권한으로 에이전트의 쓰기를 확인하고, 관리자 콘솔은 소유자 인증으로 관리 작업을 보호합니다.
애플리케이션은 변경과 행위자·증거를 함께 기록하고, 독립 검토로 최종 판단을 남깁니다.
PostgreSQL에는 장부와 추가 전용 이력이 저장되며, CLI가 스키마와 권한을 적용합니다.

## 모듈

빌드는 Gradle 다중 모듈 프로젝트입니다.

| 모듈 | 내용 |
|---|---|
| `ledger-domain` | 집합체, 값 타입, 상태 규칙, 오류 코드와 도구 이름 목록인 `ToolContract` |
| `ledger-attestation` | `_hostAttestation` 와이어 도우미와 RFC 8785 정규형 JSON 유틸리티입니다. 훅과 서버가 함께 씁니다(JDK만 사용) |
| `ledger-files` | 소유자 전용 POSIX/ACL 파일·폴더 보호와 `NOFOLLOW_LINKS` 검사입니다. 서버, CLI, 훅이 함께 씁니다(JDK만 사용) |
| `ledger-database` | Spring Data JDBC 저장소, 스키마 파일 `V{n}__*.sql`과 그 적용기, 등록된 JDBC 드라이버의 적재기 |
| `ledger-application` | 응용 서비스: 쓰기 관문, 영수증, 이력, 쓰기와 읽기 연산, 세션 권한 부여 |
| `ledger-mcp-server` | Spring Boot MCP 서버(`ledger-mcp-server.jar`) |
| `ledger-admin-web` | 소유자용 Spring Boot 관리 웹(`ledger-admin-web.jar`) |
| `ledger-cli` | 소유자용 명령줄 도구(`ledger-cli.jar`) |
| `ledger-seed` | 새 설치의 시드 번들(기본 결함 분류와 설치 기본값을 담은 H2 파일 하나)과 그 읽기 코드. `ledger-cli`만 사용합니다 |
| `ledger-hook-client` | 훅의 가벼운 클라이언트(`ledger-client-hook.jar`, `java.base`만 사용), 통신 형식, 상태 폴더, 주소 파일 |
| `ledger-client-hook` | 훅의 상주 프로그램(`ledger-hook-resident.jar`, Spring Boot 애플리케이션) |
| `ledger-migrate-1x` | 1.x 설치를 2.0으로 옮기는 별도의 도구(`ledger-migrate-1x.jar`, 1.x에서 업그레이드 참조). `ledger-domain`, `ledger-database`, `ledger-application`을 사용하고, 명령줄 기반과 비밀 저장소를 위해 `ledger-cli`의 클래스(plain jar이며 그 모듈의 라이브러리는 가져오지 않습니다)도 사용하므로 비밀 저장소는 서비스의 것과 같습니다. 다른 모듈은 이 모듈을 사용하지 않습니다 |
| `ledger-system-test` | 시험 코드만 있는 모듈: 빌드한 jar를 그 컴퓨터의 PostgreSQL에 대해 실행하는, 패키지된 제품의 시스템 시험 |
| `ledger-arch-checks` | 시험 코드만 있는 모듈: 모듈 간 아키텍처, 빌드 스크립트, SQL·트랜잭션 경계 및 의존성 규칙 검증 |
