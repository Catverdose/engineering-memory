# Troubleshooting

## 기동

### `docker compose up`에서 ollama가 GPU 오류로 실패

- 원인: `ollama` 서비스가 `driver: nvidia` GPU를 예약함
- 해결: NVIDIA Container Toolkit 설치. GPU 없이 돌리려면 `docker-compose.yml`의 `ollama.deploy` 블록을 지움(CPU 추론이라 매우 느림)

### `http://localhost`에 접속되지 않음

```bash
docker compose ps caddy nginx
docker compose logs --tail=50 caddy
```

| 원인 | 해결 |
|---|---|
| `SITE_ADDRESS`가 도메인임. Caddy는 그 도메인 요청만 받음 | 로컬에서는 `SITE_ADDRESS`를 비움(`:80`). 운영 서버에서는 `https://<도메인>`으로 접속 |
| Caddy가 뜨지 않음 | 로그에서 `Caddyfile` 파싱 오류, 80·443 포트 충돌 확인 |
| nginx 포트를 직접 열려고 함 | nginx는 compose 내부에만 열림. 외부 진입은 Caddy만 씀 |

### 도메인 HTTPS 인증서가 발급되지 않음

- 원인: DNS가 서버 IP를 가리키지 않거나, 외부에서 80·443에 접속할 수 없음
- 확인: `docker compose logs caddy`의 인증서 발급 오류, 방화벽·공유기 포트포워딩
- 해결 확인: `curl -fsS https://<도메인>/readyz`

### `/readyz`가 503

`{"backend":"NOT_READY"}` 또는 `UNREACHABLE`이 나옴.

```bash
docker compose ps
docker compose exec ollama ollama list
docker compose logs --tail=100 backend
```

| 원인 | 해결 |
|---|---|
| 모델 미설치 (가장 흔함). Ollama 컨테이너가 healthy여도 모델이 있다는 뜻은 아님 | `docker compose exec ollama ollama pull bge-m3`, `... pull exaone3.5:7.8b` |
| 백엔드 기동 중 | `start_period` 60초 동안 기다림 |
| DB 비밀번호 불일치 | `.env`의 `DB_PASSWORD`와 기존 DB 볼륨 초기 비밀번호가 같은지 확인 |

해결 확인: `curl -fsS http://localhost/readyz`가 `{"backend":"UP","status":"UP"}`

### 로컬 백엔드가 다른 Ollama를 봄

- 증상: 모델을 pull했는데 로컬 백엔드 health가 `DOWN`
- 원인: 호스트에 설치된 Ollama(11434)와 Docker Ollama가 동시에 떠 있고 백엔드가 다른 쪽을 봄
- 해결: `.env`의 `OLLAMA_PORT`와 `OLLAMA_BASE_URL` 포트를 맞추고 그 인스턴스에 모델을 pull

### 로그인 비밀번호를 모름

- `ADMIN_PASSWORD`를 비웠다면 최초 기동 로그에 한 번만 나옴: `docker compose logs backend | grep -A4 "최초 owner"`
- 사용자가 이미 있으면 `ADMIN_PASSWORD`를 바꿔도 반영되지 않음. 최초 owner는 사용자가 0명일 때만 만들어짐

## 색인

### 문서가 `색인 실패`(FAILED)에 머묾

- 원인: 대개 임베딩 모델 미설치나 Ollama 연결 실패. 백엔드 로그의 `문서 색인 실패` 줄에서 원인 클래스 확인
- 해결: 원인을 고친 뒤 문서의 `재색인` 클릭. FAILED는 자동 재시도하지 않음

### 문서가 `색인 대기`(PENDING)에 오래 머묾

- 채팅이 계속 이어지면 색인이 배치마다 최대 30초 양보함. 로그 `채팅이 계속 이어져 색인이 더 기다리지 않고 진행합니다`
- 큐가 가득 차면 10초 주기 복구 작업이 다시 예약함. 그대로 두면 처리됨

### 임베딩 모델을 바꾼 뒤 검색이 안 됨

- 이전 모델로 만든 청크는 검색에서 빠짐. 목록의 `모델 변경 · 재색인 필요` 문서를 재색인
- 공용 자료는 백엔드를 다시 띄우면 동기화가 옛 모델 문서를 자동으로 재색인함
- 차원이 1024가 아닌 모델이면 `schema.sql`의 `vector(1024)`와 `OLLAMA_EMBEDDING_DIMENSIONS`도 함께 바꿔야 함

### 공용 자료가 보이지 않음

```bash
docker compose logs backend | grep "공용 기본 지식"
```

| 로그 | 원인 | 해결 |
|---|---|---|
| `임베딩 모델이 준비되지 않아 ... 미룹니다` | 모델 미설치 | 모델을 pull하면 30초 안에 자동 진행 |
| `동기화 실패. DB 스키마가 공용 문서(owner_id NULL)를 지원하는지 확인하세요` | 공용 문서 도입 전에 만든 DB 볼륨 | 아래 `schema.sql` 재적용 후 백엔드 재시작 |
| `동기화를 끕니다(knowledge.seed.enabled=false)` | 설정으로 꺼짐 | 설정 확인 |
| 로그 없음 | 동기화는 기동당 한 번만 실행됨 | 백엔드 재시작 |

화면에서는 `내 지식`의 범위를 `공용 자료`로 바꿔야 보임. 기본 범위는 `내 자료`임.

### 같은 범용 자료가 개인 자료와 공용 자료에 두 번 나옴

- 원인: 공용 자료 도입 전에 seed 등록 스크립트로 범용 자료를 개인 자료로 넣어 둔 경우
- 해결: `내 지식`에서 범위 `내 자료`, 태그 `일반정보`로 찾아 개인 사본을 삭제. 공용 사본은 그대로 남음

## 대화

### 답변이 항상 "근거를 찾지 못했습니다" (`NO_CONTEXT`)

| 원인 | 확인 |
|---|---|
| 문서가 READY가 아님 | 지식 목록 상태 |
| 검색 범위가 너무 좁음 | 범위 초기화 후 재질문 |
| 공용 자료가 개인 기록보다 더 비슷하게 잡힘 | 답변 근거 목록의 `공용` 표시. 프로젝트·기술 범위를 지정해 재질문 |
| 유사도가 임계값 0.45 미만 | `local` 프로파일 DEBUG 로그의 `bestSimilarity` |

### `MODEL_BUSY`(503) 또는 `TOO_MANY_REQUESTS`(429)

- `MODEL_BUSY`: 동시 채팅 50건 초과. 잠시 뒤 재시도
- `TOO_MANY_REQUESTS`: IP별 요청 제한. `Retry-After` 초만큼 대기. WebSocket에서는 같은 연결의 이전 답변이 끝나기 전에 다시 물어도 발생

### 여러 사용자가 한꺼번에 요청 제한에 걸림

- 원인: 모든 요청이 같은 IP(프록시 IP)로 보임. 클라이언트 IP가 전달되지 않는 상태
- 확인: gateway 로그의 `client_hash`가 사용자와 상관없이 같은 값인지 확인
- 해결: nginx의 `set_real_ip_from`·`real_ip_header` 설정과 `forwarded.inc`가 그대로인지 확인. 구조는 [architecture](architecture.md#클라이언트-ip와-scheme-전달) 참고

### SSE 답변이 한꺼번에 도착함

- 원인: 중간 프록시의 응답 버퍼링
- 기본 구성은 Caddy(SSE 즉시 전달), nginx `proxy_buffering off`, gateway `X-Accel-Buffering: no`로 처리함. Caddy 앞에 CDN 등 다른 프록시를 두면 그쪽 버퍼링도 꺼야 함

### WebSocket이 연결되지 않음

- 프론트엔드는 실패 시 자동으로 SSE로 전환함 (하단 `전송: SSE` 표시)
- 다른 출처에서 접속하면 거부됨. 필요하면 `chat.websocket.allowed-origins`에 출처를 명시
- HTTPS에서만 실패하면 `X-Forwarded-Proto`가 `https`로 전달되는지 확인. `http`로 전달되면 같은 출처 검사에서 거부됨

## 운영

### `schema.sql`을 고쳤는데 DB에 반영되지 않음

- 원인: `/docker-entrypoint-initdb.d` 스크립트는 새 볼륨을 만들 때만 실행됨
- 해결: 스키마를 직접 다시 적용: `docker compose exec -T postgres psql -U postgres -d engineering_assistant < backend/src/main/resources/db/schema.sql`
  - `schema.sql`은 여러 번 실행해도 되도록 `IF NOT EXISTS`, `DROP NOT NULL`, 존재 확인 블록으로 작성돼 있음
  - 공용 자료 도입(owner_id NULL 허용, 공용 청크 외래키, 공용 제목·해시 고유 인덱스)도 이 방법으로 기존 DB에 반영함
- 데이터를 버려도 되면 `docker compose down -v` 후 재기동. 모든 문서·대화·계정이 삭제됨

### `.env`에 넣은 설정이 로컬 실행에서 무시됨

- 원인: 로컬 백엔드는 `.env`를 properties 파일로 읽음. `${DB_HOST}`처럼 yml에서 자리표시자로 참조하는 키만 적용되고, `RAG_SIMILARITY_THRESHOLD` 같은 환경변수식 이름은 `rag.similarity-threshold`로 바인딩되지 않음
- Docker는 `env_file`로 OS 환경변수가 되므로 적용됨. 즉 같은 `.env`라도 실행 방식에 따라 결과가 다름
- 해결: 로컬 전용 값은 `application-local.yml`에 적음

### 파일 업로드가 413

- nginx는 `/api/knowledge/documents`, `/documents/upload`, `/documents/{id}`에만 21MB를 허용하고 나머지는 1MB임. 서버 제한은 20MB

## 주의할 동작

- PDF 문서는 본문을 텍스트로 수정할 수 없음. 메타데이터 수정 또는 새 파일 등록만 가능
- 메타데이터만 바꿔도 version이 오르고 재색인됨. 색인이 끝날 때까지 그 문서는 검색에서 빠짐
- 같은 내용 파일은 제목이 달라도 중복으로 거절됨 (원본 바이트 기준)
- 답변 도중 서버가 재시작되면 그 답변은 `FAILED`로 남음
- `docker compose down -v`는 DB, 모델, Caddy 인증서 볼륨을 모두 지움
