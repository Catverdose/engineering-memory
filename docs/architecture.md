# Architecture

## Mental model

```text
Browser (vanilla JS)
  → nginx          정적 파일, 경로별 body 크기·timeout, SSE/WS 버퍼링 해제
  → Go gateway     request-id, IP별 요청 제한, 경로별 timeout, SSE flush, WS 프록시
  → Spring Boot    인증, owner 격리, 색인, 검색, 대화, 모델 호출
      → PostgreSQL + pgvector   문서·청크·벡터·대화
      → Ollama                  bge-m3(임베딩), exaone3.5:7.8b(생성)
```

## 문서 색인 흐름

```text
등록/수정/재색인 요청
  → document 저장 (PENDING, version 증가)
  → 색인 큐 (문서별로 합침, 동시 2건)
  → prepare: 행 잠금, indexing_attempt UUID 발급
  → 청크 분할 (Markdown 제목 단위 → 1200자, 150자 겹침)
  → 배치 임베딩 (32개씩, 채팅 중이면 최대 30초 양보)
  → 한 트랜잭션에서 기존 청크 삭제 → 새 청크·벡터 저장 → READY
  실패 → FAILED
```

- 등록 API는 색인을 기다리지 않고 `202 Accepted` + `PENDING`을 반환함
- 저장 직전에 version·상태·attempt가 그대로인지 확인함. 색인 도중 문서가 바뀌면 그 결과는 버리고 새 요청이 다시 색인함
- 큐가 가득 차도 문서는 `PENDING`으로 남음. 10초마다 도는 복구 작업과 기동 시 복구가 `PENDING` 문서를 다시 예약함
- `FAILED`는 자동 재시도하지 않음. 재색인 요청으로만 다시 돔
- 임베딩 입력에는 본문 외에 제목·유형·프로젝트·기술·태그·섹션 제목을 붙임. 그래서 메타데이터만 바뀌어도 재색인함

## 대화 흐름

```text
질문
  → beginTurn: 대화 행 잠금, USER 메시지 + GENERATING 상태 ASSISTANT 메시지 저장
  → 검색어 = 질문 + 최근 대화(최대 2400자)
  → 벡터 검색: 후보 top-k×4 → 유사도 0.45 미만 제외 → 문서당 3개 → 최대 5개
  → 근거 없음 → NO_CONTEXT 안내문 저장, 모델 호출 안 함
  → 프롬프트 조립: [System] [Personal Knowledge] [Recent Conversation] [User Question], 전체 10,000자 이하
  → 생성 (SSE·WS는 스트리밍, 일반 HTTP는 한 번에)
  → completeTurn: 답변·근거 저장 (COMPLETED)
  실패 → FAILED
```

- 세 전송 방식(HTTP, SSE, WebSocket)이 모두 `ChatOrchestrator` 하나를 거침. 전송 방식이 달라도 상태·근거가 같음
- 응답의 근거 목록은 검색 결과 전체가 아니라 프롬프트에 실제로 들어간 청크임
- 프롬프트 예산은 지식을 먼저 채우고 남은 공간에 최신 대화부터 넣음
- 서버가 답변 도중 재시작되면 기동 시 `GENERATING` 메시지를 `FAILED`로 바꿈
- 프론트엔드 기본 전송은 SSE임. WebSocket 연결이 실패하면 SSE로 자동 전환함

## 설계 결정과 불변식

| 항목 | 내용 | 이유 |
|---|---|---|
| owner 격리 | ownerId는 인증된 principal에서만 얻음(`OwnerContext`). 요청 본문의 값은 무시함. 모든 조회 조건에 owner가 들어감 | 남의 자료가 검색·조회되는 사고 방지 |
| DB 격리 | `document_chunk`, `chat_message`는 `(id, owner_id)` 복합 외래키로 부모를 가리킴 | 코드 실수가 있어도 다른 owner 데이터에 연결 불가 |
| 벡터 검색 순서 | owner·READY·현재 version·현재 임베딩 모델 조건을 `ORDER BY`·`LIMIT` 전에 적용함 | 가까운 이웃이 남의 자료라서 결과 자리를 차지하는 일 방지 |
| exact scan | HNSW 인덱스를 쓰지 않음 | 개인 규모 데이터에서 정확도 우선. 규모가 커지면 재검토 |
| 모델 변경 감지 | 청크마다 `embedding_model` 저장. 현재 모델과 다르면 검색에서 빠지고 `needsReindex=true` | 차원·공간이 다른 벡터가 섞이는 일 방지 |
| 근거 없으면 생성 안 함 | 검색 결과가 비면 LLM을 호출하지 않음 | 근거 없는 답을 사용자 경험처럼 꾸며내는 일 방지 |
| 채팅 우선 | 채팅은 동시 50건까지, 넘으면 즉시 `MODEL_BUSY`. 색인은 채팅이 없을 때까지 배치마다 최대 30초 양보 | 단일 GPU에서 대화 지연 최소화, 색인이 영원히 밀리지 않게 상한 둠 |
| 프롬프트 예산 검사 | `max-prompt-chars × 3 + num-predict + 64 ≤ num-ctx`가 아니면 기동 실패 | UTF-8 최악 조건에서도 컨텍스트 초과로 잘리지 않게 함 |
| 중복 문서 | owner 안에서 원본 SHA-256이 같으면 거절 | 같은 근거가 중복으로 잡히는 일 방지 |
| 스키마 관리 | `ddl-auto: none`. `schema.sql`은 새 DB 볼륨 생성 시에만 실행됨 | 앱이 스키마를 몰래 바꾸지 않게 함. 마이그레이션 도구는 미도입 |

## Timeout 사슬

바깥 계층일수록 길게 잡아 안쪽이 먼저 끝나게 함.

| 경로 | Ollama read | backend | gateway | nginx |
|---|---:|---:|---:|---:|
| 일반 채팅 `POST /api/chat/messages` | 180s | – | 240s (slow) | 300s |
| SSE `.../messages/stream` | 180s | 300s (emitter) | 360s | 420s |
| WebSocket `/api/chat/ws` | 180s | 600s (idle) | 30m | 2100s |
| 문서 등록·재색인 | – | – | 240s (slow) | 300s |
| 그 밖의 `/api/*` | – | – | 30s | 60s |

## 요청 제한

| 계층 | 대상 | 기준 |
|---|---|---|
| gateway | `/api/chat/*` (WS 핸드셰이크 포함) | IP당 버스트 120, 1초에 1개 충전 |
| backend | `/api/chat/*`, WS 메시지 | IP당 버스트 10, 6초에 1개 충전 (`local`은 60 / 2초) |
| backend | `POST /api/auth/login` | IP당 버스트 5, 60초에 1개 충전 |
| backend | 모델 동시 호출 | 채팅 50건 |
| backend | WebSocket 연결 하나 | 동시에 답변 1개 |
