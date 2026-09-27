# Quickstart

## 완료 기준

- `/readyz`가 `{"backend":"UP","status":"UP"}`를 반환함
- 문서 하나를 등록해 `검색 가능`(READY)이 되고, 그 문서를 근거로 한 답변과 근거 문서 목록이 나옴

## 경로 선택

| 상황 | 경로 |
|---|---|
| 전체 기능 사용 (권장) | A. Docker Compose 전체 실행 |
| 도메인으로 공개 | D. 도메인 배포 (A 이후) |
| 백엔드 코드 수정·디버깅 | B. 인프라만 Docker, 백엔드는 로컬 |
| 화면만 수정 | C. 목 서버 |

## A. Docker Compose 전체 실행

사전 요구사항

- Docker Compose
- NVIDIA GPU + NVIDIA Container Toolkit. `ollama` 서비스가 GPU를 예약하므로 없으면 기동 실패함
- 포트 80·443 사용 가능 (Caddy). 모델 저장 공간 약 6GB

```bash
cp .env.example .env
```

`.env`에서 최소 두 값을 채움.

- `DB_PASSWORD`: PostgreSQL 비밀번호
- `ADMIN_PASSWORD`: 최초 owner 계정 비밀번호. 비워 두면 임의 비밀번호를 만들어 백엔드 로그에 한 번만 출력함

`SITE_ADDRESS`는 비워 둠. 비우면 Caddy가 `:80`으로 모든 호스트의 HTTP를 받음.

```bash
docker compose up -d --build
docker compose exec ollama ollama pull bge-m3
docker compose exec ollama ollama pull exaone3.5:7.8b
```

### 검증

```bash
curl -fsS http://localhost/healthz   # gateway 생존: {"status":"UP"}
curl -fsS http://localhost/readyz    # 백엔드·DB·모델: {"backend":"UP","status":"UP"}
```

`readyz`는 두 모델이 모두 설치돼야 `UP`임. 503이면 [troubleshooting](troubleshooting.md#readyz가-503) 참고.

### 공용 기본 지식 확인

모델이 준비되면 30초 안에 백엔드가 공용 기본 지식 336건을 자동으로 등록하고 색인함. 별도 명령은 없음.

```bash
docker compose logs backend | grep "공용 기본 지식 동기화"
# expected: 공용 기본 지식 동기화: 등록=336, 갱신=0, 재색인=0, 삭제=0, 유지=0, 실패=0
```

이전 카탈로그(371건)로 이미 등록한 DB는 합쳐진 항목이 등록·갱신·삭제로 나뉘어 집계됨.

`내 지식` 화면에서 범위를 `공용 자료`로 바꾸면 목록이 보임. 색인이 모두 끝나 `검색 가능`이 되기까지 몇 분 걸림.

### 첫 질문까지

1. http://localhost 접속, `ADMIN_USERNAME` / `ADMIN_PASSWORD`로 로그인
   - 비밀번호를 비워 뒀다면 `docker compose logs backend | grep -A4 "최초 owner"`로 확인
2. `내 지식` → `자료 등록` → 텍스트를 붙여넣고 `저장하고 색인`
3. 목록 상태가 `색인 대기` → `검색 가능`으로 바뀔 때까지 `새로고침`
4. `대화`에서 등록한 내용을 질문
5. 답변 아래 `근거 문서 N건`에 방금 등록한 문서가 보이면 완료

### 개인 초기 자료 등록 (선택)

이 프로젝트·UBot-BE 기록과 학습 노트 62건을 로그인한 계정의 개인 자료로 등록함. 공용 자료와 달리 자동으로 들어가지 않음. 자세한 동작은 [seed/README.md](../seed/README.md).

```bash
node scripts/import-project-knowledge.mjs --dry-run   # 검증만
node scripts/import-project-knowledge.mjs             # http://localhost 에 등록
```

## B. 백엔드만 로컬 실행

사전 요구사항: Java 21, Docker Compose

```bash
docker compose up -d postgres ollama
docker compose exec ollama ollama pull bge-m3
docker compose exec ollama ollama pull exaone3.5:7.8b
cd backend
./gradlew bootRun          # Windows: gradlew.bat bootRun
```

- 프로파일은 기본 `local`임. DEBUG 로그, 느슨한 채팅 요청 제한 적용
- 설정은 `backend/../.env`를 읽음. `DB_HOST=localhost`, `OLLAMA_BASE_URL` 포트는 `OLLAMA_PORT`(기본 11434)와 맞출 것

### 검증

```bash
curl -fsS http://localhost:8080/actuator/health
# expected: {"status":"UP"}
```

이 경로에는 Caddy·nginx·gateway가 없어 화면은 뜨지 않음. API는 [reference/api.md](reference/api.md) 참고.

## C. 목 서버로 화면만 실행

사전 요구사항: Node.js 20 이상 (외부 의존성 없음)

```bash
node frontend/mock/server.js     # http://localhost:5173
```

- 아무 아이디·비밀번호로 로그인됨
- 시작할 때 개인 자료 62건([seed/project-knowledge.json](../seed/project-knowledge.json))과 공용 자료 336건(공용 카탈로그)을 READY 상태로 불러옴. 이후 변경은 메모리에만 있고 재시작하면 사라짐
- 답변은 검색 없이 고정 예시 문장임. 실제 검색·답변 품질은 A 경로로 확인
- 질문에 아래 키워드를 넣으면 해당 상황을 재현함

| 키워드 | 재현 상황 |
|---|---|
| `!nocontext` | 근거 없음 (`NO_CONTEXT`) |
| `!busy` | 모델 동시 요청 초과 (`MODEL_BUSY`, 503) |
| `!limit` | 요청 제한 (`TOO_MANY_REQUESTS`, 429) |
| `!fail` | 답변 도중 오류 이벤트 |
| `!cut` | 답변 도중 연결 끊김 |

## D. 도메인 배포

사전 요구사항: A 완료, 도메인 DNS A 레코드가 서버 IP를 가리킴, 외부에서 80·443 접속 가능

`.env`에 아래 값을 넣고 다시 띄움.

```bash
SITE_ADDRESS=catverdose.xyz
BACKEND_PROFILES=docker,prod
```

```bash
docker compose up -d
```

- Caddy가 인증서를 자동으로 받아 HTTPS를 제공함
- `prod`는 secure 쿠키를 켜고, `ADMIN_PASSWORD`가 비어 있으면 기동을 거부함

### 검증

```bash
curl -fsS https://catverdose.xyz/readyz
# expected: {"backend":"UP","status":"UP"}
```

## 다음 단계

- 구조와 처리 흐름 → [architecture.md](architecture.md)
- 설정값 조정 → [reference/configuration.md](reference/configuration.md)
