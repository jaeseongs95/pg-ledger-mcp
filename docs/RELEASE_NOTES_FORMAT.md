# Release notes format

## English

Every release of pg-ledger-mcp has one release note. This file defines its format; the English and the Korean text of the format are in this one file, and so are the two languages of every release note.

### Rules

- One file per version, `docs/release-notes/<version>.md`, named with the version in Semantic Versioning form (`2.0.0`). Tags are written with a leading `v` (`v2.0.0`); the file name has none.
- The file starts with the title `# pg-ledger-mcp <version>`.
- The English part follows under the heading `## English`. The Korean part follows under a heading of the same level that is the name of the Korean language written in Korean. The two parts are in the same file; they are not linked to each other and neither is a translation file.
- Each part has the nine sections of the table below, as headings of level three, in this order. A section that has nothing to report says so in one sentence; it is never omitted and never left empty.
- Both parts state the same facts. Code (tool names, commands, setting keys, error tokens, values) is written in code spans or code blocks and is identical in both parts. Formal wording is used; no colloquial expressions.
- The main README links every release note and this format, and is brought up to date with every release.
- A major version is required when a contract of the clients changes: a tool name or an input key, the keys of a result, the form of `_hostAttestation`, or a setting that a deployment must change. The section Breaking changes lists each such change.

### Sections

| Section | Content |
|---|---|
| Summary | what the release is, in a few sentences, and why the version number changed as it did |
| Breaking changes | every change that makes an existing client, setting or data stop working as before: the old behavior, the new behavior and what to do |
| Migration | the ordered steps from the previous version, with the exact commands, how to check each step, and how to go back |
| New | what was added |
| Changed | what behaves differently without breaking a contract |
| Removed | what no longer exists, and what replaces it |
| Security | defects and weaknesses fixed, and changes of the trust boundary |
| Requirements | what a host, a client and a database must provide for this version |
| Known limitations | what this version does not do, or does not yet do, that a user could expect |

### Checks

The documentation tests of `ledger-cli` (DOC-023 to DOC-029 in `docs/testing/TEST_CASES.md`) require that this file holds both languages and the nine sections, and that every release note follows them and states the same code in both parts.

## 한국어

pg-ledger-mcp의 모든 릴리스에는 릴리스 노트가 하나 있습니다. 이 파일은 그 형식을 정의합니다. 형식의 영어 본문과 한국어 본문은 이 파일 하나에 있으며, 모든 릴리스 노트의 두 언어도 한 파일에 함께 둡니다.

### 규칙

- 버전마다 파일 하나, `docs/release-notes/<version>.md`이며 파일 이름은 유의적 버전(Semantic Versioning) 형태의 버전입니다(`2.0.0`). 태그는 앞에 `v`를 붙이지만(`v2.0.0`) 파일 이름에는 붙이지 않습니다.
- 파일은 제목 `# pg-ledger-mcp <version>`으로 시작합니다.
- 영어 부분은 제목 `## English` 아래에 오고, 한국어 부분은 `## 한국어` 아래에 옵니다. 두 부분은 같은 파일에 있으며 서로 링크하지 않고, 어느 쪽도 별도의 번역 파일이 아닙니다.
- 각 부분은 아래 표의 아홉 개 섹션을 3단계 제목으로 이 순서대로 가집니다. 보고할 내용이 없는 섹션은 한 문장으로 그렇게 적으며, 생략하거나 비워 두지 않습니다.
- 두 부분은 같은 사실을 적습니다. 코드(도구 이름, 명령, 설정 키, 오류 토큰, 값)는 코드 스팬이나 코드 블록으로 쓰며 두 부분에서 동일합니다. 공식적인 표현을 쓰고 구어체 표현은 쓰지 않습니다.
- 메인 README는 모든 릴리스 노트와 이 형식을 링크하며, 릴리스마다 최신 상태로 갱신합니다.
- 클라이언트의 계약이 바뀌면 주 버전을 올려야 합니다. 도구 이름이나 입력 키, 결과의 키, `_hostAttestation`의 형식, 배포에서 바꿔야 하는 설정이 해당합니다. 호환되지 않는 변경 섹션에 그러한 변경을 각각 적습니다.

### 섹션

| 섹션 | 내용 |
|---|---|
| 요약 | 릴리스가 무엇인지 몇 문장으로 적고, 버전 번호가 그렇게 바뀐 이유를 적습니다 |
| 호환되지 않는 변경 | 기존 클라이언트, 설정, 데이터가 이전처럼 동작하지 않게 되는 모든 변경: 이전 동작, 새 동작, 해야 할 일 |
| 이전 절차 | 이전 버전에서 올라오는 순서 있는 절차, 정확한 명령, 단계별 확인 방법, 되돌리는 방법 |
| 추가 | 새로 추가한 것 |
| 변경 | 계약을 깨지 않으면서 다르게 동작하는 것 |
| 제거 | 더 이상 존재하지 않는 것과 그 대체물 |
| 보안 | 수정한 결함과 약점, 신뢰 경계의 변경 |
| 요구 사항 | 이 버전을 쓰기 위해 호스트, 클라이언트, 데이터베이스가 갖추어야 할 것 |
| 알려진 제한 | 사용자가 기대할 수 있으나 이 버전이 하지 않거나 아직 하지 않는 것 |

### 확인

`ledger-cli`의 문서 시험(`docs/testing/TEST_CASES.md`의 DOC-023부터 DOC-029까지)은 이 파일이 두 언어와 아홉 개 섹션을 담고 있는지, 모든 릴리스 노트가 이를 따르며 두 부분에서 같은 코드를 적는지 확인합니다.
