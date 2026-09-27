# 개인 초기 자료

`project-knowledge.json`(schemaVersion 2)은 로그인한 계정의 **개인 자료**로 등록할 초기 데이터 62건임. 모든 사용자가 보는 범용 기술 자료는 여기가 아니라 공용 카탈로그(`backend/src/main/resources/knowledge/reference-knowledge.json`, 336건)에 있고, 백엔드가 기동할 때 자동으로 반영함([architecture](../docs/architecture.md#개인-자료와-공용-자료)).

| 구분 | 건수 | 출처 |
|---|---:|---|
| engineering-memory 프로젝트 자료 | 38 | 저장소 안 문서·코드(`sourcePath`) |
| UBot-BE 프로젝트 자료·경험 | 17 | GitHub PR·이슈·코드(`sourceUri`), 공식 문서 |
| 개인 학습 노트·트러블슈팅 | 7 | 공식 문서(`sourceUri`) |

- UBot-BE 자료, 학습 노트, engineering-memory 자료 29건은 `engineering-memory-merged-knowledge.json`에서 가져옴. 프로젝트가 비어 있던 UBot-BE 경험·트러블슈팅 7건은 프로젝트를 `UBot-BE`로 채움
- engineering-memory 29건은 2026-09-28 코드·설정·문서와 대조해 고친 뒤 넣음. Caddy 도입, 공용 자료, 바뀐 문서 경로를 반영했고, 겹치던 기존 요약 6건은 이 29건에 합쳐 뺐음
- UBot-BE 자료는 외부 저장소 기준이라 사실 확인을 하지 않았음
- `.env` 값과 비밀번호는 본문에 넣지 않음
- 목 서버는 시작할 때 이 자료를 개인 자료로, 공용 카탈로그를 공용 자료로 불러옴

## 등록

서비스가 실행 중이고 owner 계정으로 로그인할 수 있어야 함.

```bash
node scripts/import-project-knowledge.mjs --dry-run   # 양식·원본 경로만 검증, 서버 호출 없음
node scripts/import-project-knowledge.mjs
```

| 항목 | 기본값 | 덮어쓰기 |
|---|---|---|
| 서버 주소 | `http://localhost` | `KNOWLEDGE_BASE_URL`. `SITE_ADDRESS`가 도메인이면 `https://<도메인>`으로 지정 |
| 로그인 | `.env`의 `ADMIN_USERNAME`·`ADMIN_PASSWORD` | `KNOWLEDGE_USERNAME`·`KNOWLEDGE_PASSWORD`. 비밀번호가 자동 생성됐거나 다른 계정일 때 |

- localhost가 아닌 주소에는 HTTPS로만 비밀번호를 보냄
- `--dry-run`은 `sourcePath` 파일이 저장소에 실제로 있는지도 확인함. 원본 문서를 옮기거나 이름을 바꾸면 여기도 같이 고쳐야 함

## 다시 실행할 때

제목으로 내 기존 문서를 찾음.

| 기존 문서 상태 | 동작 |
|---|---|
| 없음 | 새로 등록 |
| 본문이 같음 | 유지 |
| 이전 초기 데이터 9건 형식(`원본 경로: …` + 원본 전문), 버전 1, 원본 파일과 정확히 일치 | 짧은 본문으로 갱신 |
| 그 밖에 본문이 다름 | 덮어쓰지 않고 건너뜀. 수동 확인 필요 |

seed 본문을 고친 뒤 다시 실행하면 이미 등록된 문서는 "건너뜀"이 됨. 반영하려면 화면에서 수정하거나 해당 문서를 지우고 다시 실행함.

예전 버전의 이 스크립트는 범용 기술 자료도 개인 자료로 넣었음. 지금은 같은 자료가 공용으로 들어가므로 개인 사본은 지워도 됨([troubleshooting](../docs/troubleshooting.md#같은-범용-자료가-개인-자료와-공용-자료에-두-번-나옴)).
