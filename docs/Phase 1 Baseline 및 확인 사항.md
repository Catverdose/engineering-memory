## Phase 1 Baseline

현재 Engineering Memory의 기본 RAG 파이프라인 동작을 확인했다.

```text
Knowledge 등록
→ Chunking
→ Embedding
→ PostgreSQL/pgvector 저장
→ Vector Search
→ RAG Context
→ Local LLM 답변
```

### 현재 설정

- Embedding Model: `bge-m3`
- Embedding Dimension: `1024`
- Generation Model: `exaone3.5:7.8b`
- Chunk Size: `1200 chars`
- Chunk Overlap: `150 chars`
- Top-K: `5`
- Similarity Threshold: `0.55`
- Vector Search: Exact Scan
- Vector DB: PostgreSQL + pgvector

## 확인된 사항

### Docker / Local 실행 환경 분리 필요

로컬 Ollama와 Docker Ollama가 동시에 실행될 경우 서로 다른 Ollama 인스턴스를 바라볼 수 있다.

실행 위치에 따라 주소를 명확히 구분한다.

```text
Local Backend
→ PostgreSQL: localhost
→ Ollama: localhost:<host-port>

Docker Backend
→ PostgreSQL: postgres:5432
→ Ollama: ollama:11434
```

환경별 주소와 인증정보는 `.env` 및 Spring Profile을 통해 관리한다.

### Docker Ollama 모델 준비 필요

Ollama 컨테이너가 `healthy`라고 해서 필요한 모델까지 설치되어 있다는 의미는 아니다.

Embedding 모델이 없는 상태에서는 문서 등록은 가능하지만 색인이 실패할 수 있다.

필요 모델:

```text
bge-m3
exaone3.5:7.8b
```

Docker Compose 기동 과정에서 필요한 모델이 없으면 자동으로 pull하도록 구성하는 것이 필요하다.

### 문서 색인 상태

문서는 등록 후 비동기로 색인된다.

```text
PENDING
→ Chunk 생성
→ Embedding 생성
→ Vector 저장
→ READY
```

`READY` 상태의 문서만 RAG 검색 대상으로 사용된다.

### DB Schema 관리

현재 Hibernate 설정:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: none
```

DB 스키마는 애플리케이션이 자동 수정하지 않는다.

또한 Docker의 `/docker-entrypoint-initdb.d` 스크립트는 새로운 PostgreSQL Volume을 최초 생성할 때만 실행된다.

따라서 기존 Volume을 유지한 상태에서 `schema.sql`을 변경하면 DB Schema가 코드와 달라질 수 있다.

향후 스키마 변경이 늘어나면 Flyway 도입을 검토한다.

### RAG 검색 품질

문서 등록, Embedding, pgvector 저장 및 RAG 답변 생성은 정상 동작한다.

다만 현재 검색 품질 관련 설정은 아직 최적화되지 않았다.

특히:

```text
similarity-threshold = 0.55
chunk-size = 1200
chunk-overlap = 150
```

값은 Engineering Memory의 실제 개발 문서 데이터 기준으로 충분히 검증되지 않았다.

현재 확인된 답변은 관련 문서를 검색해 근거 기반으로 생성할 수 있으나, 짧거나 모호한 질문에서는 검색 결과 또는 답변 품질이 달라질 수 있다.

## 다음 개선 대상

Phase 1의 현재 상태를 baseline으로 유지하고 다음 항목을 하나씩 비교한다.

1. Chunk Size / Overlap
2. Similarity Threshold
3. Top-K
4. Embedding Model
5. Reranker
6. Prompt
7. Generation Model

여러 설정을 동시에 변경하지 않고 하나씩 변경하여 검색 품질 변화의 원인을 확인한다.

또한 Embedding Model 변경을 쉽게 하기 위해 모델별 Dimension 및 재색인 전략을 이후 별도로 설계한다.