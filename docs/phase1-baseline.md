# Phase 1 Baseline

Phase 1에서 기본 RAG 파이프라인이 동작함을 확인한 기록. Phase 2 실험의 비교 기준으로 씀. 이후 계획은 [phase2-plan.md](phase2-plan.md)에 있음.

```text
Knowledge 등록
→ Chunking
→ Embedding
→ PostgreSQL/pgvector 저장
→ Vector Search
→ RAG Context
→ Local LLM 답변
```

## 기준 설정

| 항목 | 값 |
|---|---|
| Embedding Model | `bge-m3` (1024차원) |
| Generation Model | `exaone3.5:7.8b` |
| Chunk Size / Overlap | 1200자 / 150자 |
| Top-K | 5 |
| Similarity Threshold | 0.45 (처음 0.55, 2026-09-23 변경) |
| 문서당 최대 청크 | 3 |
| 프롬프트 상한 | 10,000자 |
| Vector Search | Exact Scan (근사 인덱스 없음) |
| Vector DB | PostgreSQL 17 + pgvector |

## 유사도 임계값 관찰

고정 평가 질문 없이 수동으로 질문해 본 결과임. Phase 2에서 평가 세트로 다시 검증해야 함.

| 임계값 | 관찰 |
|---|---|
| 0.55 | 근거를 거의 찾지 못함 |
| 0.5 | 유의미한 결과. 질문 단어가 문서와 비슷하면 찾지만 못 찾을 때도 있음. 뜻이 같은 영어·한국어 단어를 다른 단어로 취급하는 것으로 보임 |
| 0.4 | 근거 문서 2개, 두 문서 내용이 모두 답변에 포함됨. 두 문서 내용이 비슷해 추가 확인 필요 |
| 0.3 | 근거 문서 2개 |
| 0.0 | 실험 중 `.env`에 둔 값. 사실상 필터 없음 |

기본값은 0.45로 변경함. `.env`의 `RAG_SIMILARITY_THRESHOLD`는 Docker 실행에서만 기본값을 덮어씀([configuration](reference/configuration.md) 참고).

## 확인된 사항

### 실행 환경별 주소 분리

로컬 Ollama와 Docker Ollama가 동시에 떠 있으면 백엔드가 다른 인스턴스를 볼 수 있음. 실행 위치별 주소를 구분함.

```text
Local Backend
→ PostgreSQL: localhost:5432
→ Ollama: localhost:<OLLAMA_PORT>

Docker Backend
→ PostgreSQL: postgres:5432
→ Ollama: ollama:11434
```

주소와 인증정보는 `.env`와 Spring 프로파일로 관리함.

### Ollama 모델 준비

- Ollama 컨테이너가 `healthy`여도 모델이 설치됐다는 뜻은 아님
- 임베딩 모델이 없으면 문서 등록은 되지만 색인이 `FAILED`가 됨
- 필요 모델: `bge-m3`, `exaone3.5:7.8b`
- Compose 기동 시 모델 자동 pull은 아직 없음

### 문서 색인 상태

등록 후 비동기로 색인함. `PENDING → 청크 생성 → 임베딩 → 벡터 저장 → READY`. `READY` 문서만 검색 대상임.

### Markdown 문서 등록

- Markdown 등록 → 제목·본문 분리 → 청크 → 임베딩 → 검색 → 근거 기반 답변까지 정상 동작 확인
- 서로 다른 문서의 청크를 함께 검색해 한 답변을 만드는 것도 확인
- 문제: 답변에 Markdown 기호가 그대로 보임. 프론트엔드가 답변을 평문(`textContent`)으로 표시하고, 시스템 프롬프트는 마크다운을 쓰지 말라고 지시하지만 모델이 따르지 않는 경우가 있음. 렌더링을 도입할지 프롬프트 준수를 강화할지 결정 필요

### DB 스키마 관리

- `spring.jpa.hibernate.ddl-auto: none`. 앱이 스키마를 바꾸지 않음
- `/docker-entrypoint-initdb.d`의 `schema.sql`은 PostgreSQL 볼륨을 처음 만들 때만 실행됨
- 기존 볼륨에서 `schema.sql`을 고치면 DB와 코드가 어긋날 수 있음. 스키마 변경이 늘면 Flyway 도입 검토

### RAG 검색 품질

- 등록, 임베딩, 저장, 근거 기반 답변은 정상 동작함
- 위 기준 설정은 실제 개발 문서 기준으로 충분히 검증되지 않음
- 짧거나 모호한 질문에서는 검색 결과와 답변 품질이 흔들림
- Phase 1의 목적은 최적 품질이 아니라 이후 실험의 기준이 되는 동작 가능한 Baseline 확보였음
