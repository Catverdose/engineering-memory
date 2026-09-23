# Quickstart

## 완료 기준

- `/readyz`가 `{"backend":"UP","status":"UP"}`를 반환함
- 문서 하나를 등록해 `검색 가능`(READY)이 되고, 그 문서를 근거로 한 답변과 근거 문서 목록이 나옴

## 경로 선택

| 상황 | 경로 |
|---|---|
| 전체 기능 사용 (권장) | A. Docker Compose 전체 실행 |
| 백엔드 코드 수정·디버깅 | B. 인프라만 Docker, 백엔드는 로컬 |
| 화면만 수정 | C. 목 서버 |

## A. Docker Compose 전체 실행

사전 요구사항

- Docker Compose
- NVIDIA GPU + NVIDIA Container Toolkit. `ollama` 서비스가 GPU를 예약하므로 없으면 기동 실패함
- 포트 80 사용 가능. 모델 저장 공간 수 GB

```bash
cp .env.example .env
```

`.env`에서 최소 두 값을 채움.

- `DB_PASSWORD`: PostgreSQL 비밀번호
- `ADMIN_PASSWORD`: 최초 owner 계정 비밀번호. 비워 두면 임의 비밀번호를 만들어 백엔드 로그에 한 번만 출력함

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

### 첫 질문까지

1. http://localhost 접속, `ADMIN_USERNAME` / `ADMIN_PASSWORD`로 로그인
   - 비밀번호를 비워 뒀다면 `docker compose logs backend | grep -A4 "최초 owner"`로 확인
2. `내 지식` → `자료 등록` → 텍스트를 붙여넣고 `저장하고 색인`
3. 목록 상태가 `색인 대기` → `검색 가능`으로 바뀔 때까지 `새로고침`
4. `대화`에서 등록한 내용을 질문
5. 답변 아래 `근거 문서 N건`에 방금 등록한 문서가 보이면 완료

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
- 설정은 `backend/../.env`를 읽음. `DB_HOST=localhost`, `OLLAMA_BASE_URL`은 호스트에 노출된 Ollama 포트(`OLLAMA_PORT`, 기본 11434)와 맞출 것

### 검증

```bash
curl -fsS http://localhost:8080/actuator/health
# expected: {"status":"UP"}
```

이 경로에는 nginx·gateway가 없어 화면은 뜨지 않음. API는 [reference/api.md](reference/api.md) 참고.

## C. 목 서버로 화면만 실행

사전 요구사항: Node.js (외부 의존성 없음)

```bash
node frontend/mock/server.js     # http://localhost:5173
```

- 아무 아이디·비밀번호로 로그인됨. 데이터는 메모리에만 있음
- 질문에 아래 키워드를 넣으면 해당 상황을 재현함

| 키워드 | 재현 상황 |
|---|---|
| `!nocontext` | 근거 없음 (`NO_CONTEXT`) |
| `!busy` | 모델 동시 요청 초과 (`MODEL_BUSY`, 503) |
| `!limit` | 요청 제한 (`TOO_MANY_REQUESTS`, 429) |
| `!fail` | 답변 도중 오류 이벤트 |
| `!cut` | 답변 도중 연결 끊김 |

## 다음 단계

- 구조와 처리 흐름 → [architecture.md](architecture.md)
- 설정값 조정 → [reference/configuration.md](reference/configuration.md)
