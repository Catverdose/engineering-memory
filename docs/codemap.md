# Codemap

기능에서 코드 위치를 찾는 문서. 백엔드 경로는 `backend/src/main/java/com/engineeringmemory/` 기준.

## 최상위

| 경로 | 내용 |
|---|---|
| `backend/` | Spring Boot 서버 |
| `gateway/` | Go 리버스 프록시 |
| `frontend/public/` | 정적 화면 (nginx가 그대로 서빙) |
| `frontend/mock/` | 백엔드 없는 개발용 목 서버 |
| `infra/` | nginx, PostgreSQL 초기화, Prometheus 설정 |
| `docker-compose.yml` | 전체 실행 구성 |

## 백엔드

| 기능 | 위치 |
|---|---|
| 채팅 조합 (검색 → 프롬프트 → 생성 → 저장) | `application/chat/ChatOrchestrator.java` |
| 채팅 진입, 모델 자리 확보, 오류 변환 | `chat/service/ChatService.java` |
| HTTP·SSE 채팅 API | `chat/controller/ChatController.java` |
| SSE 이벤트 전송 | `streaming/service/StreamService.java` |
| WebSocket 채팅 | `websocket/handler/ChatWebSocketHandler.java` |
| 대화·메시지 저장, 히스토리 | `conversation/service/ConversationService.java` |
| 재기동 시 GENERATING 회수 | `conversation/service/InterruptedConversationRecovery.java` |
| 벡터 검색 후보 선별 | `rag/service/RetrievalService.java` |
| 프롬프트 조립, 시스템 지시문, 예산 | `rag/service/ContextBuilder.java` |
| 벡터 검색 SQL, 벡터 저장 | `knowledge/repository/DocumentChunkVectorRepository.java` |
| 문서 목록·필터 SQL | `knowledge/repository/DocumentListRepository.java` |
| 문서 등록·수정·삭제, 중복 검사 | `knowledge/service/DocumentService.java` |
| 파일 검증, PDF·텍스트 추출 | `knowledge/service/KnowledgeFileExtractor.java` |
| 청크 분할 | `knowledge/service/DocumentChunker.java` |
| 색인 큐, 합치기, 복구 | `knowledge/service/DocumentIndexingService.java` |
| 색인 결과 저장 (트랜잭션) | `knowledge/service/DocumentIndexWriter.java` |
| 문서 상태 전이 (PENDING/READY/FAILED) | `knowledge/entity/Document.java` |
| Ollama 호출 (임베딩, 생성, 스트리밍, 모델 확인) | `llm/client/OllamaClient.java` |
| provider 선택 (OLLAMA/VLLM) | `llm/service/LlmService.java` |
| 채팅 우선·색인 양보 | `llm/workload/ModelWorkloadGate.java` |
| 모델 대기·적재 시간 로그 | `llm/observability/ModelCallStats.java` |
| `/actuator/health`의 모델 상태 | `llm/health/LlmHealthIndicator.java` |
| 로그인·로그아웃·me | `auth/controller/AuthController.java` |
| 인가 규칙, CSRF | `auth/config/SecurityConfig.java` |
| 인증에서 ownerId 추출 | `auth/security/OwnerContext.java` |
| 최초 owner 계정 생성 | `auth/service/InitialOwnerBootstrap.java` |
| 요청 제한 필터 | `traffic/filter/RateLimitFilter.java` |
| 오류 코드 목록 | `common/exception/ErrorCode.java` |
| 예외 → 응답 변환 | `common/exception/GlobalExceptionHandler.java` |
| request-id 전파 | `common/web/RequestIdFilter.java` |
| 설정 바인딩 record | `aiconfig/config/*Properties.java`, `*/config/*Properties.java` |
| 설정 기본값 | `backend/src/main/resources/application.yml` |
| DB 스키마 | `backend/src/main/resources/db/schema.sql` |

`user/`, `faq/`, `embedding/exception/` 등 `.gitkeep`만 있는 패키지는 이후 Phase 자리임.

## gateway

| 기능 | 위치 |
|---|---|
| 경로별 라우팅·timeout·제한 적용 | `internal/router/router.go` |
| 일반 프록시, X-Forwarded 처리, 오류 응답 | `internal/backend/client.go` |
| SSE 프록시 (즉시 flush) | `internal/sse/` |
| WebSocket 프록시 | `internal/websocket/proxy.go` |
| IP별 토큰 버킷 | `internal/middleware/rate_limit.go` |
| 환경변수 로드·timeout 순서 검증 | `internal/config/config.go` |
| `/healthz`, `/readyz` | `internal/health/health.go` |

## frontend

| 기능 | 위치 |
|---|---|
| 채팅 화면, 대화 목록, 검색 범위 | `public/index.html`, `public/assets/chat.js` |
| 지식 관리 화면 | `public/knowledge.html`, `public/assets/knowledge.js` |
| API 클라이언트, CSRF, 오류 변환 | `public/assets/api.js` |
| SSE / WS 전송, 자동 전환 | `public/assets/chat-sse.js`, `chat-ws.js`, `chat-transport.js` |
| 디자인 토큰 | `public/assets/tokens.css` |
