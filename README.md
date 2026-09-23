# engineering-memory

개인 개발 기록(프로젝트 문서, 장애 기록, 기술 결정, 학습 노트, 로그)을 저장하고, 그 기록을 근거로 답하는 RAG 기반 엔지니어링 지식 어시스턴트.

- 대상: 자기 개발 기록을 다시 찾아 쓰려는 개발자, 이 저장소를 개발·운영하는 사람
- 기능: 문서 등록·자동 색인, 범위(프로젝트·기술·유형·기간) 지정 검색, 근거 문서가 붙는 멀티턴 대화
- 비대상: 범용 챗봇. 등록한 자료에서 근거를 못 찾으면 모델을 부르지 않고 `NO_CONTEXT`로 답함
- 계정: 관리자가 발급한 owner 계정만 있음. 회원가입 없음
- 상태: Phase 1 완료. 다음은 Phase 2 (검색 품질 개선) — [로드맵](docs/roadmap.md)

```text
Browser → nginx(:80) → Go gateway(:8000) → Spring Boot(:8080) → PostgreSQL+pgvector / Ollama
```

## Quick start

요구사항: Docker Compose, NVIDIA GPU + NVIDIA Container Toolkit(Ollama 컨테이너가 GPU를 예약함), 80번 포트, 모델용 디스크 약 6GB

```bash
cp .env.example .env        # DB_PASSWORD, ADMIN_PASSWORD 값 채울 것
docker compose up -d --build
docker compose exec ollama ollama pull bge-m3
docker compose exec ollama ollama pull exaone3.5:7.8b
```

### 정상 동작 확인

```bash
curl -fsS http://localhost/readyz
# expected: {"backend":"UP","status":"UP"}
```

`UP`이면 http://localhost 에 접속해 `ADMIN_USERNAME` / `ADMIN_PASSWORD`로 로그인. 로컬 개발, 목 서버, 첫 질문까지의 확인 절차는 [Quickstart](docs/quickstart.md)에 있음.

## 문서 지도

| 목적 | 문서 |
|---|---|
| 처음 실행, 로컬 개발 | [docs/quickstart.md](docs/quickstart.md) |
| 구조, 처리 흐름, 설계 결정 | [docs/architecture.md](docs/architecture.md) |
| 기능별 코드 위치 | [docs/codemap.md](docs/codemap.md) |
| HTTP·SSE·WebSocket API, 오류 코드 | [docs/reference/api.md](docs/reference/api.md) |
| 환경변수, 설정값 | [docs/reference/configuration.md](docs/reference/configuration.md) |
| 장애 해결, 주의할 동작 | [docs/troubleshooting.md](docs/troubleshooting.md) |
| RAG 기준 설정과 확인 사항 | [docs/Phase 1 Baseline 및 확인 사항.md](<docs/Phase 1 Baseline 및 확인 사항.md>) |
| 로드맵 | [docs/roadmap.md](docs/roadmap.md) |

## Compatibility

| 구성 | 버전 |
|---|---|
| Java / Spring Boot / Gradle | 21 / 4.1.1 / 9.7.1 (wrapper) |
| Go | 1.22+ (Docker 빌드는 1.24) |
| PostgreSQL / pgvector | 17 (`pgvector/pgvector:pg17`) |
| nginx | 1.27 |
| 임베딩 모델 | `bge-m3` (1024차원, DB `vector(1024)`와 일치해야 함) |
| 생성 모델 | `exaone3.5:7.8b` |

## 테스트

```bash
cd backend && ./gradlew test               # 단위 테스트
cd backend && ./gradlew integrationTest    # docker compose의 postgres·ollama가 떠 있어야 함
cd gateway && go test ./...
```

## Status

**Phase 1 완료** (2026-09-23)

- 확인: backend 단위·통합 테스트, gateway 테스트 전부 통과. Docker Compose 전체 기동 후 `/readyz` UP
- 선반영: 문서 수정·재색인, 범위 검색, 근거·원본 제공(Phase 2), SSE/WebSocket 분리(Phase 3), 멀티턴 대화(Phase 5)
- 남은 과제: Ollama 모델 자동 pull, 스키마 마이그레이션 도구, Prometheus 수집 엔드포인트
- 다음: Phase 2 — [Phase 1 Baseline](<docs/Phase 1 Baseline 및 확인 사항.md>) 기준으로 청크·임계값·Top-K 등을 하나씩 비교

라이선스 [MIT](LICENSE).
