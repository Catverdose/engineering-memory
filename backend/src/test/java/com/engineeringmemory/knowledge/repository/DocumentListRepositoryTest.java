package com.engineeringmemory.knowledge.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DocumentListRepositoryTest {

	@Test
	@DisplayName("목록 쿼리는 owner를 먼저 고정하고 원문 TEXT/BYTEA를 SELECT하지 않는다")
	void listProjectionIsOwnerScopedAndLightweight() {
		assertThat(DocumentListRepository.BASE_FROM_WHERE)
				.contains("d.owner_id = :ownerId");
		assertThat(DocumentListRepository.SELECT_COLUMNS)
				.doesNotContain("original_content", "d.content");
	}
}
