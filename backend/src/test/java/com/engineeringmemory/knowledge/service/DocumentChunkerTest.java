package com.engineeringmemory.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.engineeringmemory.knowledge.config.KnowledgeProperties;

class DocumentChunkerTest {

	private final DocumentChunker chunker = new DocumentChunker(
			new KnowledgeProperties(20 * 1024 * 1024L, 500, 2_000_000, 200, 20, 100, 32, 2));

	@Test
	@DisplayName("Markdown 제목을 청크 출처 문맥으로 보존한다")
	void preservesMarkdownHeading() {
		var chunks = chunker.split("""
				# 장애 원인
				Nginx가 SSE 응답을 버퍼링했습니다.

				## 해결
				proxy_buffering off를 적용했습니다.
				""");

		assertThat(chunks).hasSize(2);
		assertThat(chunks.get(0).heading()).isEqualTo("장애 원인");
		assertThat(chunks.get(1).heading()).isEqualTo("해결");
		assertThat(chunks).extracting(DocumentChunker.ChunkDraft::index)
				.containsExactly(0, 1);
	}

	@Test
	@DisplayName("긴 본문은 설정된 크기 이하의 겹치는 청크로 나눈다")
	void splitsLongContentWithinConfiguredLimit() {
		String content = "가".repeat(450);

		var chunks = chunker.split(content);

		assertThat(chunks).hasSizeGreaterThan(1);
		assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.content().length()).isLessThanOrEqualTo(200));
	}
}
