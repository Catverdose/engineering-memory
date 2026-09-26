# 프로젝트 지식 초기 데이터

`project-knowledge.json`은 지식 등록 양식에 맞춘 주제별 본문·메타데이터·출처를 담는다. 목 서버는 시작할 때 같은 자료를 화면에 표시한다. 실제 백엔드 DB는 별도 등록이 필요하다.

자료 구성: 프로젝트 요구사항·아키텍처·API·설정·운영·RAG를 주제별로 나눈 프로젝트 기록과, 실제 사용 스택(Go, Java 21, Spring Boot·MVC·Security·WebSocket·Data JPA, Jakarta Validation, Lombok, JUnit, PostgreSQL, pgvector, Docker·Compose, nginx, Ollama와 bge-m3·exaone3.5, SSE, WebSocket, Fetch, Gradle, PDFBox)의 범용 기술 정보. 범용 자료는 프로젝트를 비워 두고 `DOCUMENT` 또는 `TECH_DOC`으로 분류하며 기술 메타데이터와 공식 원문 URL을 넣는다. 프로젝트 자료는 프로젝트 `engineering-memory`와 저장소 원본 경로를 기록한다. 발생일은 기준일이 명시된 로드맵에만 넣었다. 범용 지식과 개인의 실제 경험을 섞지 않는다.

실제 서비스가 실행되고 owner 계정으로 로그인할 수 있을 때:

```powershell
node scripts/import-project-knowledge.mjs --dry-run
node scripts/import-project-knowledge.mjs
```

기본 주소는 `http://localhost`이고, 로그인 이름·비밀번호는 저장소의 `.env`에 있는 `ADMIN_USERNAME`·`ADMIN_PASSWORD`를 읽는다. 최초 기동 때 비밀번호가 자동 생성됐거나 별도 계정으로 로그인해야 한다면 `KNOWLEDGE_USERNAME`·`KNOWLEDGE_PASSWORD` 환경변수로 덮어쓴다. 다른 주소는 `KNOWLEDGE_BASE_URL`로 지정한다. 원격 HTTP에는 비밀번호를 보내지 않는다.

이전 초기 데이터 9건은 본문이 정확히 기존 원본과 일치하고 버전이 1일 때만 짧은 본문으로 갱신한다. 이미 수정된 자료와 제목이 겹치는 자료는 덮어쓰지 않는다. 새로 등록·갱신한 문서는 먼저 `PENDING`으로 보이며, Ollama 임베딩 색인이 끝나 `READY`가 되어야 채팅 검색에 포함된다. `.env`와 비밀번호는 자료 본문에 넣지 않는다.
