# engineering-memory

개인 개발 기록(프로젝트 문서, 장애 기록, 기술 결정, 학습 노트, 로그)을 저장하고, 그 기록을 근거로 답하는 RAG 기반 엔지니어링 지식 어시스턴트.

- 대상: 자기 개발 기록을 다시 찾아 쓰려는 개발자, 이 저장소를 개발·운영하는 사람
- 기능: 문서 등록·자동 색인, 범위(프로젝트·기술·유형·기간) 지정 검색, 근거 문서가 붙는 멀티턴 대화
- 기본 지식: 범용 기술 문서 336건을 공용 자료로 자동 등록함. 모든 사용자가 읽을 수 있고, 답변에서는 개인 기록과 구분함
- 비대상: 범용 챗봇. 등록한 자료에서 근거를 못 찾으면 모델을 부르지 않고 `NO_CONTEXT`로 답함
- 계정: 관리자가 발급한 owner 계정만 있음. 회원가입 없음
- 상태: Phase 1 완료, Phase 2 진행 중 — [Phase 2 계획](docs/phase2-plan.md)

```text
Browser → Caddy(:80/:443) → nginx → Go gateway(:8000) → Spring Boot(:8080) → PostgreSQL+pgvector / Ollama
```

## Quick start

요구사항: Docker Compose, NVIDIA GPU + NVIDIA Container Toolkit(Ollama 컨테이너가 GPU를 예약함), 80·443번 포트, 모델용 디스크 약 6GB

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

`UP`이면 http://localhost 에 접속해 `ADMIN_USERNAME` / `ADMIN_PASSWORD`로 로그인. 공용 기본 지식은 모델 준비 후 자동으로 들어옴. 개인 초기 자료 등록, 로컬 개발, 목 서버, 도메인 배포는 [Quickstart](docs/quickstart.md)에 있음.

기존 DB 볼륨을 쓰고 있다면 공용 자료를 받도록 스키마를 한 번 다시 적용해야 함([troubleshooting](docs/troubleshooting.md#schemasql을-고쳤는데-db에-반영되지-않음)).

## 문서 지도

| 목적 | 문서 |
|---|---|
| 목적, 범위, 원칙 | [docs/product-requirements.md](docs/product-requirements.md) |
| 처음 실행, 로컬 개발, 도메인 배포 | [docs/quickstart.md](docs/quickstart.md) |
| 구조, 처리 흐름, 설계 결정 | [docs/architecture.md](docs/architecture.md) |
| 기능별 코드 위치 | [docs/codemap.md](docs/codemap.md) |
| HTTP·SSE·WebSocket API, 오류 코드 | [docs/reference/api.md](docs/reference/api.md) |
| 환경변수, 설정값 | [docs/reference/configuration.md](docs/reference/configuration.md) |
| 장애 해결, 주의할 동작 | [docs/troubleshooting.md](docs/troubleshooting.md) |
| RAG 기준 설정과 확인 사항 | [docs/phase1-baseline.md](docs/phase1-baseline.md) |
| Phase 2 계획 | [docs/phase2-plan.md](docs/phase2-plan.md) |
| 개인 초기 자료, 공용 기본 지식 | [seed/README.md](seed/README.md) |
| 로드맵, 구현 현황 | [docs/roadmap.md](docs/roadmap.md) |

## Compatibility

| 구성 | 버전 |
|---|---|
| Java / Spring Boot / Gradle | 21 / 4.1.1 / 9.7.1 (wrapper) |
| Go | 1.22+ (Docker 빌드는 1.24) |
| PostgreSQL / pgvector | 17 (`pgvector/pgvector:pg17`) |
| Caddy / nginx | 2 / 1.27 |
| Node.js | 20 이상 (목 서버, seed 등록 스크립트) |
| 임베딩 모델 | `bge-m3` (1024차원, DB `vector(1024)`와 일치해야 함) |
| 생성 모델 | `exaone3.5:7.8b` |

## 테스트

```bash
cd backend && ./gradlew test               # 단위 테스트
cd backend && ./gradlew integrationTest    # docker compose의 postgres·ollama가 떠 있어야 함
cd gateway && go test ./...
node scripts/import-project-knowledge.mjs --dry-run   # seed 데이터 검증 (서버 호출 없음)
```

## Status

**Phase 1 완료** (2026-09-23), Phase 2 진행 중

- 확인: backend 단위·통합 테스트, gateway 테스트 통과. Docker Compose 전체 기동 후 `/readyz` UP (Caddy 추가 전 기준)
- 선반영: 문서 수정·재색인, 범위 검색, 근거·원본 제공, 공용 기본 지식 분리(Phase 2), SSE/WebSocket 분리(Phase 3), 멀티턴 대화(Phase 5)
- 남은 과제: Ollama 모델 자동 pull, 스키마 마이그레이션 도구, Prometheus 수집 엔드포인트
- 다음: [Phase 2 계획](docs/phase2-plan.md) — Knowledge Category, 검색 범위 확장, [Baseline](docs/phase1-baseline.md) 대비 설정 비교

라이선스 [MIT](LICENSE).
