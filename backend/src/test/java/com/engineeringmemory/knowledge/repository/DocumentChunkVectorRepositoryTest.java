package com.engineeringmemory.knowledge.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.engineeringmemory.aiconfig.config.OllamaProperties;

import tools.jackson.databind.ObjectMapper;

class DocumentChunkVectorRepositoryTest {

	@Test
	@DisplayName("벡터 후보를 정렬하거나 제한하기 전에 청크와 문서 owner를 모두 고정한다")
	void ownerBoundaryIsInsideBaseSearchQuery() {
		String sql = DocumentChunkVectorRepository.BASE_SEARCH_SQL;

		assertThat(sql).contains(
				"WHERE c.owner_id = :ownerId",
				"AND d.owner_id = :ownerId",
				"AND c.embedding_model = :embeddingModel");
		assertThat(sql).doesNotContain("ORDER BY", "LIMIT");
	}

	@Test
	@DisplayName("DB로 보낼 벡터는 차원과 유한값을 검증한다")
	void validatesVectorLiteral() {
		DocumentChunkVectorRepository repository = new DocumentChunkVectorRepository(
				new JdbcTemplate(), new ObjectMapper(),
				new OllamaProperties("http://localhost", "embed", "generate", 2, 0.2, 32768, 1024,
						java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(2)));

		assertThat(repository.toVectorLiteral(new float[] { 1.0f, -2.5f })).isEqualTo("[1.0,-2.5]");
		assertThatThrownBy(() -> repository.toVectorLiteral(new float[] { Float.NaN, 1.0f }))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> repository.toVectorLiteral(new float[] { 1.0f }))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
