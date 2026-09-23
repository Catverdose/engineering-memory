package com.engineeringmemory.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.engineeringmemory.aiconfig.config.RagProperties;
import com.engineeringmemory.embedding.service.EmbeddingService;
import com.engineeringmemory.knowledge.dto.ChunkHit;
import com.engineeringmemory.knowledge.dto.SearchScope;
import com.engineeringmemory.knowledge.enums.DocumentType;
import com.engineeringmemory.knowledge.repository.DocumentChunkVectorRepository;

class RetrievalServiceTest {

	@Test
	@DisplayName("인증 owner를 벡터 검색 단계에 전달하고 임계치 미만 근거를 제거한다")
	void appliesOwnerBoundaryBeforeAcceptingContext() {
		EmbeddingService embedding = mock(EmbeddingService.class);
		DocumentChunkVectorRepository vectors = mock(DocumentChunkVectorRepository.class);
		RetrievalService service = new RetrievalService(
				embedding, vectors, new RagProperties(5, 0.55, 12_000, 3));
		float[] queryVector = { 0.1f, 0.2f };
		SearchScope scope = SearchScope.all();
		when(embedding.embedQuery("질문")).thenReturn(queryVector);
		when(embedding.embeddingModel()).thenReturn("bge-m3");
		when(vectors.search(42L, queryVector, "bge-m3", scope, 20)).thenReturn(List.of(
				hit(1L, 10L, 0.80, "근거"),
				hit(2L, 11L, 0.54, "제외")));

		var result = service.retrieve(42L, "질문", scope);

		assertThat(result.chunks()).extracting(ChunkHit::chunkId).containsExactly(1L);
		verify(vectors).search(eq(42L), eq(queryVector), eq("bge-m3"), eq(scope), eq(20));
	}

	private static ChunkHit hit(long chunkId, long documentId, double similarity, String content) {
		return new ChunkHit(chunkId, documentId, "문서", DocumentType.NOTE,
				List.of("project"), List.of("java"), List.of(), null,
				0, null, content, similarity);
	}
}
