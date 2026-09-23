# Roadmap

표시: ✅ 완료 · `일부` 부분 구현. 기준일 2026-09-23.

## Phase 1 — Private Knowledge Assistant MVP ✅

* ✅ 개인 개발 문서, 로그, 메모, 트러블슈팅, 기술 결정 기록 등록
* ✅ Markdown / TXT / PDF / README 지원
* ✅ Chunking + Embedding + PostgreSQL/pgvector
* ✅ Metadata 기반 검색
* ✅ RAG 기반 질의응답
* ✅ Go Gateway + SSE 스트리밍
* ✅ 단일 사용자 로그인 및 Private Knowledge Base
* ✅ Docker Compose 기반 단일 서버 배포

## Phase 2 — Knowledge Ingestion & Retrieval

* ✅ 문서 수정 / 삭제 / 재색인
* Metadata 자동 추출 및 분류 보조
* 중복 문서 및 Chunk 관리 — `일부` 같은 원본 중복 등록 차단
* Query Rewrite / Rerank / Context Builder — `일부` Context Builder, 최근 대화로 검색어 보강
* ✅ 프로젝트 / 기술 / 자료 유형 / 기간 기반 검색
* 유사 장애 및 트러블슈팅 검색
* 대화나 로그에서 새로운 내용을 `Knowledge Candidate`로 생성하고 사용자 승인 후 저장
* ✅ 답변 근거 및 원본 위치 제공

## Phase 3 — AI Serving & Streaming

* AI Profile / AiConfig 관리
* ✅ Chat / Embedding 모델 설정 분리
* Ollama / vLLM 지원 — `일부` Ollama
* 모델별 생성 옵션 및 동시성 설정 — `일부` 전역 생성 옵션, 채팅 동시성 상한
* ✅ Go Gateway SSE / WebSocket 분리 구현
* Rate Limit / In-Flight Limit / Timeout / Heartbeat — `일부` Heartbeat 제외
* Prometheus 기반 모니터링
* 스트리밍 및 모델 Serving 성능 비교

## Phase 4 — Knowledge Graph & External Sources

* GraphDB 기반 개발 지식 관계 관리
* Project / Technology / Issue / Error / Solution / Decision / Experiment 관계 구성
* Vector Search + Graph Search Hybrid Retrieval
* 자동 Entity / Relationship 추출
* Git Repository / GitHub Issue / PR / Commit 연동
* 코드베이스 분석 결과 및 외부 기술 문서 데이터 소스 추가
* Knowledge Graph 탐색 및 시각화

## Phase 5 — Multi-user & Multi-turn

* 회원가입 및 사용자별 Knowledge Base
* 사용자별 Document / Vector / Graph 데이터 격리 — `일부` Document·Vector·대화
* 사용자별 AI Profile / Rate Limit / Storage 관리
* ✅ Conversation / ChatMessage 기반 멀티턴 대화
* 대화 Context / Query Rewrite / Conversation Summary — `일부` 최근 대화 8건 Context
* Private by Default 기반 선택적 공유
* `PRIVATE / SHARED / PUBLIC` 접근 정책 확장

## Core Direction

```text
Capture
  ↓
Ingestion
  ↓
Knowledge Base
  ↓
Retrieval
  ↓
AI Assistant
  ↓
New Knowledge
  ↓
Capture
```

사용할수록 개인의 개발 경험과 기술 지식이 계속 쌓이고 다시 활용되는 Engineering Knowledge Assistant를 목표로 함.
