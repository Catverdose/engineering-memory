package com.engineeringmemory.knowledge.dto;

import java.time.LocalDate;
import java.util.List;

import com.engineeringmemory.knowledge.enums.DocumentType;

public record ChunkHit(
		Long chunkId,
		Long documentId,
		String title,
		DocumentType documentType,
		List<String> projects,
		List<String> technologies,
		List<String> tags,
		LocalDate occurredOn,
		int chunkIndex,
		String heading,
		String content,
		double similarity) {

	public ChunkHit {
		projects = projects == null ? List.of() : List.copyOf(projects);
		technologies = technologies == null ? List.of() : List.copyOf(technologies);
		tags = tags == null ? List.of() : List.copyOf(tags);
	}

	public String citation() {
		StringBuilder sb = new StringBuilder();
		sb.append('[').append(documentType.getLabel()).append("] ").append(title);
		if (!projects.isEmpty()) {
			sb.append(" · 프로젝트: ").append(String.join(", ", projects));
		}
		if (!technologies.isEmpty()) {
			sb.append(" · 기술: ").append(String.join(", ", technologies));
		}
		if (occurredOn != null) {
			sb.append(" · ").append(occurredOn);
		}
		if (heading != null && !heading.isBlank()) {
			sb.append(" · ").append(heading);
		}
		return sb.toString();
	}

	public String snippet(int maxChars) {
		String flat = content.replaceAll("\\s+", " ").trim();
		return flat.length() <= maxChars ? flat : flat.substring(0, maxChars) + "…";
	}
}
