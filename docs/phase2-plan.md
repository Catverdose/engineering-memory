# Phase 2 계획

Knowledge 축적과 Retrieval 품질 개선이 목표. 비교 기준은 [phase1-baseline.md](phase1-baseline.md).

원칙

- RAG 설정은 한 번에 하나씩 바꿔 품질 변화의 원인을 확인함
- 비교 전에 고정 평가 질문과 기대 근거 문서 세트를 먼저 만듦
- 구체적인 값(임계값 등)은 미리 정하지 않고 Retrieval 벤치마크로 결정함
- 평가는 정답 문서가 Top-K에 드는지(검색)와 가져온 근거를 충실하게 해석하는지(생성)를 나눠 봄. 검색에 성공해도 생성 모델이 근거를 잘못 읽으면 틀린 답이 나옴
- 한국어·영어 교차 검색(한국어 질의→영어 문서 등)은 따로 평가함 ([phase1-baseline](phase1-baseline.md#유사도-임계값-관찰)의 관찰)

## 초기 Knowledge 확보

현재 파이프라인은 동작하지만 저장된 Knowledge가 적어 활용 범위가 좁음.

- 현재
  - 공용 기본 지식 336건: 백엔드 카탈로그(`reference-knowledge.json`)에서 기동 시 자동 등록. 모든 사용자가 읽음
  - 개인 초기 자료 62건: [seed/project-knowledge.json](../seed/project-knowledge.json), 등록 스크립트로 내 계정에 넣음 ([seed/README.md](../seed/README.md))
  - 카탈로그 안에서 제목만 달리 겹치던 주제(EC2, Kubernetes Service·Ingress, ALB, RDS 등 26묶음)는 하나씩으로 합쳐 371건에서 336건으로 줄임
- 확보 대상: 대화 기록, GitHub PR·Issue·Commit, 프로젝트 문서, 트러블슈팅 기록, 설계 결정, 실험·벤치마크, 학습 메모, 기술 레퍼런스
- 원문을 그대로 넣기보다 장기적으로 아래 흐름을 고려함

```text
Raw Source → Knowledge Candidate 추출 → 구조화 → 사용자 확인 → Knowledge 등록 → Chunk / Embedding
```

- 개인 경험과 일반 기술 지식은 구분함 (구현됨)
  - Personal Knowledge: 직접 겪은 프로젝트, 장애, 결정, 실험. 사용자 소유 문서
  - Reference Knowledge: 공식 문서, 일반 기술 지식. owner 없는 공용 문서. 프롬프트에 `(공용 자료)`로 표시

## Knowledge Category

Knowledge가 늘면 성격별 상위 분류가 필요함.

현재 상태: `documentType`(11종)이 이미 분류와 채팅 범위 필터 역할을 함. 새 Category 후보와의 관계는 아래와 같음.

| Category 후보 | 기존 documentType |
|---|---|
| TROUBLESHOOTING | `TROUBLESHOOTING` |
| DECISION | `DECISION` |
| PROJECT | `PROJECT`, `PROJECT_DOC` |
| REFERENCE | 없음. 지금은 owner 없는 공용 자료가 이 역할을 함 |
| EXPERIMENT | `EXPERIMENT` |
| NOTE | `NOTE`, `RETROSPECTIVE` |
| UNCLASSIFIED | 없음 (`DOCUMENT`, `OTHER`와 가까움) |
| 후보 없음 | `LOG` |

결정 필요

- A안: `documentType` 값을 Category 후보로 정리해 하나의 분류로 씀
- B안: `documentType`은 자료 형식, Category는 지식 성격으로 필드를 따로 둠
- 어느 쪽이든 기존 문서 이전과 재색인이 필요함. 임베딩 입력에 자료 유형 라벨이 들어가기 때문임

`Docker`, `Spring Boot`, `PostgreSQL` 같은 값은 Category가 아니라 Technology 메타데이터로 관리함.

```text
Category:   TROUBLESHOOTING
Project:    Engineering Memory
Technology: Docker, Ollama, Spring Boot
Tags:       env, localhost, port
```

### Category별 입력 양식

자유 본문 외에 성격에 맞는 구조화 양식을 제공하는 방향을 검토함.

| Category | 양식 |
|---|---|
| TROUBLESHOOTING | 증상, 원인, 해결 방법, 확인 방법, 재발 방지 |
| DECISION | 문제 상황, 선택한 방법, 검토한 대안, 선택 이유, Trade-off |
| PROJECT | 목적, 구조, 구현 내용, 사용 기술, 현재 상태, 회고 |
| REFERENCE | 개념, 핵심 내용, 사용 방법, 주의사항, 출처 |
| EXPERIMENT | 가설, 실험 환경, 비교 대상, 측정 지표, 결과, 결론 |
| NOTE | 자유 기록 |
| UNCLASSIFIED | 제목, 본문만 (빠른 기록용) |

이후 AI가 Category와 메타데이터를 추천하고 사용자가 확인해 정식 Knowledge로 전환하는 방식을 고려함.

## Chat Search Scope

이미 구현됨

- 프로젝트·기술·자료 유형·기간 범위 검색 (`ChatRequest.scope`, 대화 화면의 `검색 범위` 패널)
- 범위는 결과를 좁히기만 함. 범위 안에 근거가 없으면 `NO_CONTEXT`

새로 할 것

- `AUTO`: 질문을 보고 범위를 정함
- Category 범위: 전체, 장애·해결, 기술 결정, 프로젝트, Reference, 실험, 메모
- Fallback: 지정 범위에서 결과가 부족하면 전체 또는 `UNCLASSIFIED`까지 넓힘. 후보 수 증가나 임계값 완화도 실험함
- Category는 완전한 hard partition으로 쓰지 않음

```text
사용자 질문
→ Search Scope 결정
→ Category / Project / Technology 필터
→ Vector Search
→ 결과 충분 여부 판단
→ 필요 시 Search Scope 확장
→ RAG Context
→ Generation
```

## AI Model Routing

로드맵상 Phase 3(모델별 설정) 성격. Phase 2에서는 설계와 규칙 기반 시범 적용까지만 함.

- Embedding: 공통 모델 하나를 씀. Category마다 다른 임베딩 모델은 초기에는 적용하지 않음
- Generation: 용도별 두 단계로 나누는 방향

| Tier | 용도 |
|---|---|
| GENERAL | 과거 설정 조회, 단일 문서 기반 질문, 간단한 기술 설명, 요약, 단순 트러블슈팅 조회 |
| HARD | 여러 장애 기록 종합, 복잡한 원인 분석, 기술 대안 비교, 아키텍처·Trade-off 분석, 프로젝트 간 관계 분석 |

- Category와 모델 Tier를 1:1로 묶지 않음. 같은 TROUBLESHOOTING이라도 질문 난이도에 따라 다름
  - "전에 Ollama 포트 몇 번 썼지?" → GENERAL
  - "Docker와 로컬 Ollama 충돌 원인을 분석하고 재발 방지 구조까지 설계해줘" → HARD
- 구조: Category는 검색 범위·검색 정책·프롬프트를 정하고, 질문 복잡도는 Model Router가 GENERAL/HARD를 정함
- 처음에는 AI 분류기보다 단순 규칙 기반 라우팅부터 적용함
- Category별 임베딩 모델은 공간 분리, 다중 질의 임베딩, 결과 병합 복잡도가 커서 `EmbeddingProfile` 설계 뒤 검토함

## 다음 개선 대상

순서대로 하나씩 비교·개선함.

1. Knowledge Category (A안/B안 결정)
2. Category별 입력 템플릿
3. Chat Search Scope (AUTO, fallback)
4. 초기 Knowledge 데이터 확보
5. Chunk Size / Overlap
6. Similarity Threshold
7. Top-K
8. Embedding Model
9. Embedding Model 교체와 재색인 전략 (모델별 차원, `EmbeddingProfile`)
10. Reranker
11. Query Rewrite
12. Prompt
13. GENERAL / HARD Generation Model Routing
14. Metadata 자동 추출
15. Knowledge Candidate
16. PDF 추출 품질 (읽기 순서, 스캔 PDF OCR). 등록 자체는 Phase 1에서 지원함
17. 답변 Markdown 표시 방식 ([phase1-baseline](phase1-baseline.md#markdown-문서-등록) 참고)
18. Flyway 도입 검토
19. Vector Index 전략 (exact scan 유지 또는 HNSW)
