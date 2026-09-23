# API Reference

원본은 컨트롤러 코드와 `common/exception/ErrorCode.java`임. OpenAPI 명세는 아직 없음.

## 공통 규칙

- 인증: 세션 쿠키. `POST /api/auth/login`, `GET /api/auth/me`, `/actuator/health` 외에는 로그인 필요
- CSRF: `GET` 이외 요청은 `XSRF-TOKEN` 쿠키 값을 `X-XSRF-TOKEN` 헤더에 넣어야 함. 쿠키는 아무 API 응답에서나 발급됨
- owner: 모든 데이터는 로그인한 owner 것만 보임. 남의 id를 요청하면 `404 NOT_FOUND`로 응답함
- 오류 본문: `{"code": "...", "message": "...", "retryable": true|false}`
- `X-Request-Id`: 요청에 있으면 그대로 쓰고 없으면 만들어 응답 헤더로 돌려줌

## 인증 `/api/auth`

| Method | Path | 설명 |
|---|---|---|
| POST | `/login` | body `{username, password}`. 성공 시 `{authenticated, ownerId, username, roles}` |
| POST | `/logout` | 세션 종료 |
| GET | `/me` | 현재 사용자. 비로그인이면 `authenticated: false` |

## 채팅 `/api/chat`

요청 본문 (세 방식 공통)

```json
{
  "conversationId": 12,
  "message": "Nginx SSE 버퍼링 어떻게 해결했지?",
  "scope": {
    "projects": ["ubot"],
    "technologies": ["nginx"],
    "documentTypes": ["TROUBLESHOOTING"],
    "from": "2026-01-01",
    "to": "2026-09-30"
  }
}
```

| 필드 | 제약 |
|---|---|
| `conversationId` | 생략 시 새 대화 생성 |
| `message` | 필수, 1,000자 이하 |
| `scope` | 생략 시 전체. projects 20개, technologies 30개, documentTypes 20개 이하, `from ≤ to`. 대소문자 무시 |

| 방식 | Path | 응답 |
|---|---|---|
| HTTP | `POST /messages` | `ChatResponse` 한 번. 실패도 HTTP 200 + `status: FAILED` |
| SSE | `POST /messages/stream` | `text/event-stream`. 모델 자리가 없으면 스트림 열기 전에 `503 MODEL_BUSY` |
| WebSocket | `GET /ws` | 연결 후 요청 본문을 텍스트 메시지로 보냄. 같은 출처만 허용 |

`ChatResponse`

```json
{
  "requestId": "…",
  "conversationId": 12,
  "status": "COMPLETED | NO_CONTEXT | FAILED",
  "scope": "전체",
  "answer": "…",
  "sources": [ /* Source */ ],
  "errorCode": null,
  "retryable": false
}
```

`Source`: `documentId, chunkId, title, documentType, documentTypeLabel, projects, technologies, tags, occurredOn, chunkIndex, heading, similarity, snippet(240자)`

### 스트리밍 이벤트

SSE는 `event:` 이름, WebSocket은 `{"type": "...", "data": {...}}` 프레임으로 같은 내용을 보냄.

| 이벤트 | data | 순서 |
|---|---|---|
| `meta` | `{requestId, conversationId, scope, sources, model}` | 처음 한 번. 근거 없으면 `sources: []`, `model: null` |
| `delta` | `{text}` | 0회 이상 |
| `done` | `{reason: COMPLETED \| NO_CONTEXT}` | 정상 종료 |
| `error` | `{requestId, conversationId, code, message, retryable}` | 실패 종료. `conversationId`가 있으면 대화는 이미 생성됨 |

`done`·`error` 없이 연결이 끊기면 답변이 중간에 잘린 것임.

## 지식 `/api/knowledge`

| Method | Path | 설명 |
|---|---|---|
| POST | `/documents` (JSON) | 텍스트 등록. `DocumentCreateRequest`. `202` + `Location` + `PENDING` 문서 |
| POST | `/documents`, `/documents/upload` (multipart) | 파일 등록. part `metadata`(JSON), `file`. `202` |
| GET | `/documents` | 목록. 쿼리 `q, documentType, project, technology, tag, indexingStatus, from, to, page, size(≤100)`. 여러 값은 파라미터 반복 |
| GET | `/documents/{id}` | 상세 (추출 본문 포함) |
| PUT | `/documents/{id}` | 본문·메타데이터 교체. PDF는 불가(`415`). `202` |
| PATCH | `/documents/{id}` | 메타데이터만 수정. `DocumentMetadataRequest`. `202` |
| DELETE | `/documents/{id}` | 문서와 청크 삭제. `204` |
| POST | `/documents/{id}/reindex` | 재색인 요청. `202` |
| GET | `/documents/{id}/original` | 원본 파일 다운로드 |
| GET | `/facets` | 유형별 개수, 등록된 프로젝트·기술·태그 목록 |

`DocumentMetadataRequest`: `title`(필수, 300자), `documentType`(필수), `projects`(20개), `technologies`(30개), `tags`(50개), `occurredOn`, `sourceUri`(2,000자). `DocumentCreateRequest`는 여기에 `content`(필수)를 더함.

문서 응답의 `indexingStatus`는 `PENDING | READY | FAILED`. `needsReindex`는 READY가 아니거나 임베딩 모델이 현재 설정과 다르면 `true`.

### 파일 제약

| 항목 | 값 |
|---|---|
| 형식 | `README`(확장자 없음), `.txt`, `.md`, `.markdown`, `.log`, `.pdf` |
| 인코딩 | 텍스트는 UTF-8만 |
| 크기 | 20MB |
| PDF | 500쪽, 암호화 불가, 텍스트 추출 가능해야 함 |
| 추출 텍스트 | 2,000,000자 |
| 청크 수 | 2,500개 |
| 중복 | 같은 owner의 원본과 바이트가 같으면 `409 DUPLICATE_DOCUMENT` |

### documentType

`DOCUMENT` 일반 문서, `PROJECT` 프로젝트 자료, `TROUBLESHOOTING` 장애·트러블슈팅, `TECH_DOC` 기술 문서, `PROJECT_DOC` 프로젝트 문서, `NOTE` 개발·학습 노트, `RETROSPECTIVE` 회고, `EXPERIMENT` 실험·측정 결과, `LOG` 로그, `DECISION` 기술 선택 근거, `OTHER` 기타

## 대화 `/api/conversations`

| Method | Path | 설명 |
|---|---|---|
| GET | `/` | 목록 (최근 수정순). `page, size(≤100)` |
| GET | `/{id}` | 메시지 포함 상세. `limit(≤200, 기본 100)`, `beforeSequence`로 이전 메시지 이어 읽기. `hasMoreMessages`, `nextBeforeSequence` 반환 |
| PATCH | `/{id}` | 제목 변경 `{title}` (160자) |
| DELETE | `/{id}` | 대화와 메시지 삭제 |

메시지 `status`: `COMPLETED | GENERATING | NO_CONTEXT | FAILED`

## 오류 코드

| code | HTTP | retryable | 의미 |
|---|---:|:---:|---|
| `INVALID_REQUEST` | 400 | – | 요청 값 오류 |
| `UNAUTHORIZED` | 401 | – | 로그인 필요 |
| `INVALID_CREDENTIALS` | 401 | – | 아이디·비밀번호 불일치 |
| `FORBIDDEN` | 403 | – | 권한 없음, CSRF 토큰 누락 |
| `NOT_FOUND` | 404 | – | 없음 또는 남의 데이터 |
| `DUPLICATE_DOCUMENT` | 409 | – | 같은 내용 문서 존재 |
| `CONCURRENT_MODIFICATION` | 409 | ● | 다른 요청이 먼저 수정함 |
| `FILE_TOO_LARGE` | 413 | – | 크기·쪽수·글자 수 초과 |
| `UNSUPPORTED_FILE_TYPE` | 415 | – | 형식 불일치, PDF 본문 수정 |
| `TOO_MANY_REQUESTS` | 429 | ● | 요청 제한. `Retry-After` 헤더 참고. WS에서는 이전 답변 진행 중일 때도 사용 |
| `NO_CONTEXT` | 200 | – | 근거 없음 (채팅 상태값) |
| `MODEL_BUSY` | 503 | ● | 모델 동시 요청 상한 |
| `MODEL_UNAVAILABLE` | 503 | ● | Ollama 호출 실패, 모델 없음 |
| `CONFIGURATION_MISSING` | 503 | – | 서버 설정 누락 |
| `EXTERNAL_UNAVAILABLE` | 503 | ● | 외부 서비스 실패 |
| `INDEXING_FAILED` | 500 | ● | 색인 반영 실패 |
| `INVARIANT_VIOLATION` | 500 | – | 데이터 정합성 오류 |
| `INTERNAL_ERROR` | 500 | ● | 그 밖의 서버 오류 |

gateway·nginx가 직접 내는 코드: `GATEWAY_TIMEOUT`(504), `BACKEND_UNAVAILABLE`(502), 게이트웨이 `NOT_FOUND`(404, 허용 목록 밖 경로), nginx `FILE_TOO_LARGE`(413).
