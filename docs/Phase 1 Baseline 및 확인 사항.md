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

* Embedding Model: `bge-m3`
* Embedding Dimension: `1024`
* Generation Model: `exaone3.5:7.8b`
* Chunk Size: `1200 chars`
* Chunk Overlap: `150 chars`
* Top-K: `5`
* Similarity Threshold: `0.55` //근데 이렇게 하면 아무것도 못찾음 확인 필요함 지금은 0.0임 0.3도 근거문서가 2개임 0.4도 그럼 근데 일단 내용이 두개 다 포함됨, 근데 문서 두개가 내용이 비슷해서 더 확인해봐야할듯. 0.5의 경우 유의미함 일단 단어를 비슷하게 주면 찾음 못찾기도 하는데 영어랑 한국어가 의미가 같아도 다른 단어로 인식하는듯
* Vector Search: Exact Scan
* Vector DB: PostgreSQL + pgvector

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

### Markdown Knowledge 등록

Markdown 문서를 Knowledge로 등록한 뒤 다음 흐름이 정상적으로 동작하는 것을 확인했다.

```text
Markdown 등록
→ 본문 및 Heading 추출
→ Chunking
→ Embedding
→ pgvector 저장
→ Vector Search
→ RAG Context 구성
→ 근거 기반 답변 생성
```

서로 다른 Knowledge 문서의 Chunk를 함께 검색하여 하나의 답변을 생성하는 것도 확인했다.

다만 LLM의 Markdown 출력이 Frontend에서 escape된 상태로 표시되는 문제가 있어 렌더링 개선이 필요하다.

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
top-k = 5
```

값은 Engineering Memory의 실제 개발 문서 데이터 기준으로 충분히 검증되지 않았다.

현재 확인된 답변은 관련 문서를 검색해 근거 기반으로 생성할 수 있으나, 짧거나 모호한 질문에서는 검색 결과 또는 답변 품질이 달라질 수 있다.

현재 Phase 1의 목적은 최적의 RAG 품질을 확보하는 것이 아니라 이후 실험의 기준으로 사용할 동작 가능한 Baseline을 확보하는 것이다.

---

## Knowledge 초기 데이터 확보

현재 시스템은 RAG 파이프라인 자체는 동작하지만 저장된 Knowledge의 양이 적어 활용 범위가 제한적이다.

초기 Knowledge는 다음 자료를 중심으로 확보한다.

```text
Chat History
GitHub PR / Issue / Commit
Project Document
Troubleshooting 기록
설계 결정
실험 및 Benchmark
학습 메모
기술 Reference
```

Chat이나 PR 원문을 그대로 모두 검색 대상으로 넣기보다는 장기적으로 다음 흐름을 고려한다.

```text
Raw Source
→ Knowledge Candidate 추출
→ 구조화
→ 사용자 확인
→ Knowledge 등록
→ Chunk / Embedding
```

개인의 실제 경험과 일반 기술 지식은 구분하여 관리한다.

```text
Personal Knowledge
→ 직접 경험한 프로젝트, 장애, 결정, 실험

Reference Knowledge
→ 공식 문서 및 일반적인 기술 지식
```

---

## Phase 2 Knowledge Category

Knowledge가 증가하면 모든 자료를 하나의 검색 공간으로만 관리하기 어려워질 수 있다.

따라서 Knowledge의 성격을 나타내는 상위 Category를 추가한다.

초기 후보:

```text
TROUBLESHOOTING
DECISION
PROJECT
REFERENCE
EXPERIMENT
NOTE
UNCLASSIFIED
```

`Docker`, `Spring Boot`, `PostgreSQL` 등은 Category가 아니라 Technology Metadata로 관리한다.

예:

```text
Category
TROUBLESHOOTING

Project
Engineering Memory

Technology
Docker
Ollama
Spring Boot

Tags
env
localhost
port
```

### Category별 Knowledge 입력 양식

Category가 지정된 Knowledge는 단순 자유 본문뿐 아니라 해당 지식의 성격에 맞는 구조화된 입력 양식을 제공하는 방향을 검토한다.

#### TROUBLESHOOTING

```text
증상
원인
해결 방법
확인 방법
재발 방지
```

#### DECISION

```text
문제 상황
선택한 방법
검토한 대안
선택 이유
Trade-off
```

#### PROJECT

```text
목적
구조
구현 내용
사용 기술
현재 상태
회고
```

#### REFERENCE

```text
개념
핵심 내용
사용 방법
주의사항
출처
```

#### EXPERIMENT

```text
가설
실험 환경
비교 대상
측정 지표
결과
결론
```

#### NOTE

자유로운 기록을 우선한다.

#### UNCLASSIFIED

빠른 기록을 위해 최소한의 정보만으로 저장할 수 있도록 한다.

```text
제목
본문
```

향후 AI가 Category와 Metadata를 추천하고 사용자가 확인하여 정식 Knowledge로 전환하는 방식을 고려한다.

---

## Chat Search Scope

Knowledge Category는 저장뿐 아니라 Chat 검색 범위에도 활용한다.

사용자는 필요할 경우 검색 범위를 직접 지정할 수 있다.

```text
AUTO
전체
장애 / 해결
기술 결정
프로젝트
Reference
실험
메모
```

Project와 Technology도 추가 검색 Scope로 사용할 수 있다.

예:

```text
Category: TROUBLESHOOTING
Project: Engineering Memory
Technology: Docker
```

검색 흐름은 다음과 같은 방향을 고려한다.

```text
사용자 질문
→ Search Scope 결정
→ Category / Project / Technology Filter
→ Vector Search
→ 결과 충분 여부 판단
→ 필요 시 Search Scope 확장
→ RAG Context
→ Generation
```

Category를 완전한 Hard Partition으로 사용하지 않는다.

지정된 Category에서 충분한 결과를 얻지 못한 경우 전체 Knowledge 또는 `UNCLASSIFIED`까지 검색 범위를 확장할 수 있도록 한다.

Fallback 과정에서는 필요에 따라 후보 개수를 늘리거나 Similarity Threshold를 완화하는 방식도 실험한다.

구체적인 Threshold 값은 고정하지 않고 실제 Retrieval Benchmark를 통해 결정한다.

---

## AI Model Routing 방향

Category마다 완전히 별도의 Embedding Model을 사용하는 방식은 초기 단계에서는 적용하지 않는다.

Embedding Model은 우선 하나의 공통 모델을 사용한다.

```text
Embedding
→ Common Embedding Model
```

Generation Model은 용도에 따라 두 단계 정도로 구분하는 방향을 검토한다.

```text
Generation
├─ GENERAL
└─ HARD
```

### GENERAL

일반적인 Knowledge 조회 및 상대적으로 단순한 답변에 사용한다.

예:

```text
과거 설정 조회
단일 문서 기반 질문
간단한 기술 설명
Knowledge 요약
단순 Troubleshooting 조회
```

### HARD

여러 Knowledge를 함께 분석하거나 복잡한 추론이 필요한 질문에 사용한다.

예:

```text
여러 장애 기록 종합
복잡한 원인 분석
기술 대안 비교
Architecture 분석
Trade-off 분석
여러 프로젝트 간 관계 분석
```

Category와 Generation Model은 직접 1:1로 연결하지 않는다.

같은 `TROUBLESHOOTING` Category라도 질문 난이도에 따라 다른 Model Tier를 사용할 수 있다.

```text
"전에 Ollama 포트 몇 번 썼지?"
→ GENERAL

"Docker와 Local Ollama 충돌 원인을 분석하고 재발 방지 구조까지 설계해줘"
→ HARD
```

따라서 장기적인 구조는 다음과 같이 가져간다.

```text
Category
→ Search Scope / Retrieval Policy / Prompt 결정

Question Complexity
→ Model Router
→ GENERAL 또는 HARD
```

초기에는 복잡한 AI Classifier보다 단순한 규칙 기반 Routing부터 적용하고 필요하면 이후 개선한다.

Embedding Model을 Category마다 다르게 사용하는 방식은 Embedding Space 분리, 다중 Query Embedding, 검색 결과 병합 등의 복잡도가 발생하므로 추후 `EmbeddingProfile` 구조를 설계한 뒤 검토한다.

---

## 다음 개선 대상

Phase 1의 현재 상태를 Baseline으로 유지하고 Phase 2에서 다음 항목을 하나씩 비교하고 개선한다.

1. Knowledge Category
2. Category별 입력 Template
3. Chat Search Scope
4. 초기 Knowledge 데이터 확보
5. Chunk Size / Overlap
6. Similarity Threshold
7. Top-K
8. Embedding Model
9. Embedding Model 교체 및 Reindex 전략
10. Reranker
11. Query Rewrite
12. Prompt
13. GENERAL / HARD Generation Model Routing
14. Metadata 자동 추출
15. Knowledge Candidate
16. PDF Ingestion
17. Markdown Rendering
18. Flyway 도입 검토
19. Vector Index 전략

여러 RAG 설정을 동시에 변경하지 않고 하나씩 변경하여 검색 품질 변화의 원인을 확인한다.

특히 Embedding Model 변경을 쉽게 하기 위해 모델별 Dimension, Embedding Profile 및 재색인 전략은 별도로 설계한다.

Phase 1은 동작 가능한 RAG Baseline으로 유지하고, Phase 2부터는 Knowledge 축적과 Retrieval 품질 개선을 중심으로 진행한다.
