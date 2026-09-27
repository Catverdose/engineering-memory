package com.engineeringmemory.knowledge.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.engineeringmemory.knowledge.enums.KnowledgeOwnership;

class DocumentListRepositoryTest {

	@Test
	@DisplayName("목록 쿼리는 원문 TEXT/BYTEA를 SELECT하지 않는다")
	void listProjectionIsLightweight() {
		assertThat(DocumentListRepository.SELECT_COLUMNS)
				.doesNotContain("original_content", "d.content");
	}

	@Test
	@DisplayName("어느 범위든 내 문서와 공용 문서만 본다. 다른 사용자의 개인 문서는 조건에 들어올 수 없다")
	void everyOwnershipScopeIsLimitedToMineOrShared() {
		assertThat(KnowledgeOwnership.MINE.fromWhere())
				.contains("WHERE d.owner_id = :ownerId")
				.doesNotContain("IS NULL");
		assertThat(KnowledgeOwnership.SHARED.fromWhere())
				.contains("WHERE d.owner_id IS NULL")
				.doesNotContain(":ownerId");
		assertThat(KnowledgeOwnership.ALL.fromWhere())
				.contains("WHERE (d.owner_id = :ownerId OR d.owner_id IS NULL)");
	}
}
