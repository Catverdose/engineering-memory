package com.engineeringmemory.chat.dto.request;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import com.engineeringmemory.knowledge.dto.SearchScope;
import com.engineeringmemory.knowledge.enums.DocumentType;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ChatRequest(

		@Positive(message = "conversationId 는 양수여야 합니다.")
		Long conversationId,

		@NotBlank(message = "message 는 필수입니다.")
		@Size(max = 1000, message = "질문은 1,000자를 넘을 수 없습니다.")
		String message,

		@Valid
		Scope scope) {

	public SearchScope searchScope() {
		return scope == null ? SearchScope.all() : scope.toSearchScope();
	}

	public record Scope(
			@Size(max = 20) List<@NotBlank @Size(max = 100) String> projects,
			@Size(max = 30) List<@NotBlank @Size(max = 100) String> technologies,
			@Size(max = 20) Set<@NotNull DocumentType> documentTypes,
			LocalDate from,
			LocalDate to) {

		public SearchScope toSearchScope() {
			return new SearchScope(projects, technologies, documentTypes, from, to);
		}

		@AssertTrue(message = "검색 기간의 시작일은 종료일보다 늦을 수 없습니다.")
		public boolean isDateRangeValid() {
			return from == null || to == null || !from.isAfter(to);
		}
	}
}
