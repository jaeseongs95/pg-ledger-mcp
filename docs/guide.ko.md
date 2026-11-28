# Veridger 사용·운영 가이드

[English](guide.md) · [프로젝트 개요](../README.ko.md)

소프트웨어 개발, 영화 제작, 집필, 연구, 운영 등 모든 분야의 AI 에이전트 작업을 기록하는 범용 장부이며, 하나의 MCP 엔드포인트로 제공됩니다. 이슈, 명세, 작업, 마일스톤 기준, 산출물과 독립 검수를 누가 왜 수행했는지와 함께 기록합니다.
에이전트는 표준 MCP 도구로 읽고 쓰며, 자신의 데이터베이스 로그인을 받지 않습니다.

이것은 **개인판**입니다. 한 사람이 본인의 컴퓨터나 본인의 서버에 설치해 실행하며, 설치 대상 전체를 신뢰합니다. 두 서비스(MCP 서버와 관리 웹)는
루프백 주소 `127.0.0.1`에서만 수신합니다. 다른 컴퓨터에서 접근하게 하는 일, 곧 역방향 프록시와 TLS 인증서는 운영자가 고른 도구로 하는 운영자의 몫이며,
설치기는 프록시 설정을 쓰지 않고 인증서를 발급하지 않습니다.

2.0.0 버전은 Java 25와 Spring Boot로 다시 작성한 것이며 자체 도구 계약을 가집니다(모든 쓰기 도구는 필수 `operationKey`를 받고, 모든 응답은 `ApiResult.v1` 봉투를 쓰며, 이전 버전의 인자는 새 인자와 함께 받아들이지 않습니다).
클라이언트에 바뀐 내용은 릴리스 노트([2.0.0](release-notes/2.0.0.md), [2.1.0](release-notes/2.1.0.md), [2.1.1](release-notes/2.1.1.md), [3.0.0](release-notes/3.0.0.md), [4.0.0](release-notes/4.0.0.md), [4.0.1](release-notes/4.0.1.md), [4.1.0](release-notes/4.1.0.md), [4.2.0](release-notes/4.2.0.md), [4.3.0](release-notes/4.3.0.md), [4.3.1](release-notes/4.3.1.md), [4.3.2](release-notes/4.3.2.md), [4.4.0](release-notes/4.4.0.md), [4.4.1](release-notes/4.4.1.md), [5.0.0](release-notes/5.0.0.md), [6.0.0](release-notes/6.0.0.md), [7.0.0](release-notes/7.0.0.md))에 적습니다. 릴리스 노트의 형식은 [docs/RELEASE_NOTES_FORMAT.md](RELEASE_NOTES_FORMAT.md)에 있습니다.

## 목차

- [장부 개념](concepts.ko.md)
- [MCP 도구와 첨부](mcp.ko.md)
- [아키텍처](architecture.ko.md)
- [설치와 설정](installation.ko.md)
- [운영](administration.ko.md)
- [클라이언트 훅](client-hooks.ko.md)
- [1.x에서 마이그레이션](migration.ko.md)
- [개발과 시험](development.ko.md)

<details>
<summary>이전 가이드 링크</summary>

- <a id="개념"></a>[개념](concepts.ko.md#개념)
- <a id="mcp-도구"></a>[MCP 도구](mcp.ko.md#mcp-도구)
- <a id="첨부"></a>[첨부](mcp.ko.md#첨부)
- <a id="모듈"></a>[모듈](architecture.ko.md#모듈)
- <a id="설정"></a>[설정](installation.ko.md#설정)
- <a id="설치"></a>[설치](installation.ko.md#설치)
- <a id="linux-서버"></a>[Linux 서버](installation.ko.md#linux-서버)
- <a id="밖에서-접근하기"></a>[밖에서 접근하기](installation.ko.md#밖에서-접근하기)
- <a id="windows-개인-pc-또는-서버"></a>[Windows: 개인 PC 또는 서버](installation.ko.md#windows-개인-pc-또는-서버)
- <a id="postgresql-스키마-자동-업데이트"></a>[PostgreSQL 스키마 자동 업데이트](installation.ko.md#postgresql-스키마-자동-업데이트)
- <a id="첫-설정"></a>[첫 설정](installation.ko.md#첫-설정)
- <a id="설치-뒤"></a>[설치 뒤](installation.ko.md#설치-뒤)
- <a id="ledger-cli"></a>[ledger-cli](administration.ko.md#ledger-cli)
- <a id="관리-웹"></a>[관리 웹](administration.ko.md#관리-웹)
- <a id="클라이언트-훅"></a>[클라이언트 훅](client-hooks.ko.md#클라이언트-훅)
- <a id="1x에서-업그레이드"></a>[1.x에서 업그레이드](migration.ko.md#1x에서-업그레이드)
- <a id="가져오기가-하는-일"></a>[가져오기가 하는 일](migration.ko.md#가져오기가-하는-일)
- <a id="1x-원본의-설정"></a>[1.x 원본의 설정](migration.ko.md#1x-원본의-설정)
- <a id="빌드와-시험"></a>[빌드와 시험](development.ko.md#빌드와-시험)
- <a id="시험-데이터베이스"></a>[시험 데이터베이스](development.ko.md#시험-데이터베이스)
- <a id="자원이-작은-기계에서-시험-jvm을-제한하는-선택-속성"></a>[자원이 작은 기계에서 시험 JVM을 제한하는 선택 속성](development.ko.md#자원이-작은-기계에서-시험-jvm을-제한하는-선택-속성)
- <a id="빌드-속도"></a>[빌드 속도](development.ko.md#빌드-속도)
- <a id="구성"></a>[구성](development.ko.md#구성)

</details>

## 라이선스

veridger는 2.0 버전부터 GNU Affero General Public License v3.0 또는 (선택에 따라) 그 이후 판(`AGPL-3.0-or-later`)에 따라 배포합니다. 자세한 내용은 [LICENSE](../LICENSE), [ADDITIONAL-TERMS.ko.md](../ADDITIONAL-TERMS.ko.md), [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)를 참조하십시오.

Copyright (C) 2026 Jaeseong Song

`AGPL-3.0-or-later`는 2.0.0과 그 이후에 공개하는 모든 릴리스에 적용하며, 이후에 공개하는 1.x 계열의 유지보수 릴리스도 포함합니다. MIT License는 이미 그 라이선스로 공개한 사본, 곧 v1.0.0과 v1.1.0에만 계속 적용합니다.

라이선스 제13조에 따라, 수정한 버전을 네트워크 서비스로 운영하는 자는 그 서비스의 이용자에게 해당 소스 코드를 제공해야 합니다.
관리 웹은 각 페이지 하단에 소스 코드 링크를 보여 줍니다. 그 주소는 설정 `ledger.source.url`이며 기본값은 프로젝트의 공개 저장소입니다. 수정한 버전을 운영하는 자는
`ledger.source.url`을 그 수정한 버전의 해당 소스 코드를 제공하는 곳으로 지정해야 합니다. 이 설정은 `http` 또는 `https` 주소를 받으며, MCP 서버의 응답에는 포함되지 않습니다.
