# Configuration Reference

기본값의 원본은 `backend/src/main/resources/application.yml`(백엔드), `docker-compose.yml`(gateway), `gateway/internal/config/config.go`임. 이 문서는 요약임.

## 프로파일

| 프로파일 | 적용 | 차이 |
|---|---|---|
| `local` | 기본값 (`./gradlew bootRun`) | DEBUG 로그, 채팅 제한 60 / 2초 |
| `docker` | compose 기본값 `BACKEND_PROFILES=docker` | 프록시 헤더(`X-Forwarded-*`) 신뢰. nginx·gateway 뒤에서만 씀 |
| `prod` | 운영 시 `BACKEND_PROFILES=docker,prod` | secure 쿠키, `ADMIN_PASSWORD` 비어 있으면 기동 거부 |

- 프록시 헤더 신뢰는 `docker`에만 둠. 프록시 없이 뜨는 프로파일에서 켜면 클라이언트가 IP를 위조해 요청 제한을 피할 수 있음 (`ConfigYamlTest`가 검사함)
- `prod`의 secure 쿠키는 HTTPS에서만 전송됨. 현재 nginx 설정은 80번 HTTP뿐이라 앞단에 TLS 종료가 없으면 로그인이 유지되지 않음

## `.env`

| 변수 | 기본값 | 설명 |
|---|---|---|
| `BACKEND_PROFILES` | `docker` | compose에서 백엔드 프로파일 |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `5432` / `engineering_assistant` | compose 안에서는 `postgres:5432`로 덮어씀 |
| `DB_USERNAME` / `DB_PASSWORD` | `postgres` / – | postgres 컨테이너 초기 계정과 같아야 함 |
| `OLLAMA_PORT` | `11434` | 호스트에 노출할 Ollama 포트 (compose) |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | 로컬 백엔드용. compose 안에서는 `http://ollama:11434`로 덮어씀 |
| `OLLAMA_EMBEDDING_MODEL` | `bge-m3` | 바꾸면 모든 문서 재색인 필요 |
| `OLLAMA_GENERATION_MODEL` | `exaone3.5:7.8b` | |
| `OLLAMA_EMBEDDING_DIMENSIONS` | `1024` | DB `vector(1024)`와 같아야 함 |
| `OLLAMA_NUM_PARALLEL` | `1` | Ollama 서버 동시 처리 수 |
| `LLM_PROVIDER` | `OLLAMA` | `VLLM`은 enum만 있고 구현 없음 |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | `admin` / 빈 값 | 사용자가 한 명도 없을 때만 최초 owner 생성. `{bcrypt}…`처럼 `{`로 시작하면 인코딩된 값으로 봄 |

## 백엔드 주요 설정

| 키 | 기본값 | 설명 |
|---|---|---|
| `rag.top-k` | 5 | 프롬프트 근거 최대 청크 수 |
| `rag.similarity-threshold` | 0.45 | 이 값 미만 유사도는 버림 |
| `rag.max-prompt-chars` | 10000 | 프롬프트 전체 글자 상한 |
| `rag.max-chunks-per-document` | 3 | 문서 하나에서 가져올 최대 청크 |
| `knowledge.indexing.chunk-size-chars` | 1200 | 청크 크기 |
| `knowledge.indexing.chunk-overlap-chars` | 150 | 청크 겹침. chunk-size보다 작아야 함 |
| `knowledge.indexing.embedding-batch-size` | 32 | 임베딩 호출 한 번의 청크 수 |
| `knowledge.indexing.max-concurrent-indexing` | 2 | 동시 색인 문서 수 |
| `knowledge.indexing.recovery-poll-delay` | 10s | PENDING 복구 주기 |
| `ollama.num-ctx` / `ollama.num-predict` | 32768 / 1024 | 모델 컨텍스트 / 최대 출력 토큰 |
| `ollama.temperature` | 0.2 | |
| `ollama.read-timeout` | 180s | 모델 응답 대기 |
| `chat.model.max-in-flight` | 50 | 동시 채팅 상한. 넘으면 `MODEL_BUSY` |
| `chat.model.background-yield-timeout` | 30s | 색인이 채팅에 양보하는 최대 시간 |
| `chat.stream.timeout` | 300s | SSE 연결 수명. `ollama.read-timeout`보다 길어야 함 |
| `chat.websocket.enabled` / `path` | true / `/api/chat/ws` | |
| `chat.websocket.allowed-origins` | `[]` | 비우면 같은 출처만 허용. `*` 금지 |
| `rate-limit.chat.*` | 10 / 6s | 채팅 요청 제한 (IP 기준) |
| `rate-limit.login.*` | 5 / 60s | 로그인 요청 제한 |
| `llm.observability.queue-warn-threshold` | 2s | 모델 대기·적재가 이보다 길면 경고 로그 |

기동 시 검증하는 제약

- `rag.max-prompt-chars × 3 + ollama.num-predict + 64 ≤ ollama.num-ctx`
- `chunk-overlap-chars < chunk-size-chars`
- 설정 테스트(`ConfigYamlTest`)는 base의 제한값, 로그 레벨, 임베딩 차원, timeout 순서, 비밀값 자리표시자를 검사함. 설정을 바꾸면 `./gradlew test`로 확인할 것

## gateway 환경변수

| 변수 | 기본값 (compose 값) | 설명 |
|---|---|---|
| `GATEWAY_LISTEN_ADDR` | `:8000` | |
| `GATEWAY_BACKEND_URL` | – (`http://backend:8080`) | 필수 |
| `GATEWAY_TRUST_PROXY_HEADERS` | `false` (`true`) | nginx 뒤에 있을 때만 `true` |
| `GATEWAY_PROXY_TIMEOUT` | 30s | 일반 API |
| `GATEWAY_SLOW_TIMEOUT` | 240s | 일반 채팅, 문서 등록, 재색인. PROXY 이상이어야 함 |
| `GATEWAY_STREAM_TIMEOUT` | 360s | SSE. PROXY보다 길어야 함 |
| `GATEWAY_WEBSOCKET_TIMEOUT` | 30m | WS 연결 수명. STREAM 이상이어야 함 |
| `GATEWAY_SHUTDOWN_GRACE` | 30s | 종료 시 진행 중 요청 대기 |
| `GATEWAY_RATE_LIMIT_ENABLED` | `true` | |
| `GATEWAY_RATE_LIMIT_BURST` / `REFILL` | 60 / 2s (120 / 1s) | `/api/chat/*`만 적용 |
| `GATEWAY_BACKEND_HEALTH_PATH` | `/actuator/health` | `/readyz`가 확인할 경로 |
| `GATEWAY_LOG_LEVEL` | info | `debug` 가능 |

timeout 순서가 어긋나거나 값을 읽을 수 없으면 gateway는 기동하지 않음.
