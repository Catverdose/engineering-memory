package com.engineeringmemory.knowledge.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.engineeringmemory.knowledge.enums.DocumentType;

public record SearchScope(
		List<String> projects,
		List<String> technologies,
		Set<DocumentType> documentTypes,
		LocalDate from,
		LocalDate to) {

	private static final SearchScope ALL = new SearchScope(List.of(), List.of(), Set.of(), null, null);

	public SearchScope {
		projects = normalize(projects);
		technologies = normalize(technologies);
		documentTypes = documentTypes == null ? Set.of() : Set.copyOf(documentTypes);

		if (from != null && to != null && from.isAfter(to)) {
			throw new IllegalArgumentException(
					"검색 기간의 시작이 끝보다 늦습니다: from=" + from + ", to=" + to);
		}
	}

	private static List<String> normalize(List<String> values) {
		if (values == null) {
			return List.of();
		}
		return values.stream()
				.filter(v -> v != null && !v.isBlank())
				.map(v -> v.trim().toLowerCase(Locale.ROOT))
				.distinct()
				.toList();
	}

	public static SearchScope all() {
		return ALL;
	}

	public boolean isAll() {
		return projects.isEmpty() && technologies.isEmpty() && documentTypes.isEmpty()
				&& from == null && to == null;
	}

	public String describe() {
		if (isAll()) {
			return "전체";
		}
		StringBuilder sb = new StringBuilder();
		if (!projects.isEmpty()) {
			sb.append("프로젝트=").append(String.join(",", projects)).append(' ');
		}
		if (!technologies.isEmpty()) {
			sb.append("기술=").append(String.join(",", technologies)).append(' ');
		}
		if (!documentTypes.isEmpty()) {
			sb.append("종류=").append(documentTypes.stream().map(DocumentType::getLabel).toList()).append(' ');
		}
		if (from != null || to != null) {
			sb.append("기간=").append(from == null ? "" : from).append('~').append(to == null ? "" : to);
		}
		return sb.toString().trim();
	}
}
