package com.engineeringmemory.knowledge.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.engineeringmemory.knowledge.enums.DocumentType;

class SearchScopeTest {

	@Test
	@DisplayName("검색 필터는 대소문자와 중복을 정규화하지만 owner를 포함하지 않는다")
	void normalizesUserSelectedFilters() {
		SearchScope scope = new SearchScope(
				List.of(" UBot ", "ubot"),
				List.of("Spring", " spring "),
				Set.of(DocumentType.PROJECT), null, null);

		assertThat(scope.projects()).containsExactly("ubot");
		assertThat(scope.technologies()).containsExactly("spring");
		assertThat(scope.describe()).contains("프로젝트=ubot", "기술=spring", "종류=");
		assertThat(SearchScope.class.getRecordComponents())
				.extracting(java.lang.reflect.RecordComponent::getName)
				.doesNotContain("ownerId");
	}

	@Test
	@DisplayName("검색 시작일이 종료일보다 늦으면 즉시 거부한다")
	void rejectsInvertedDateRange() {
		assertThatThrownBy(() -> new SearchScope(
				List.of(), List.of(), Set.of(),
				LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 1)))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
