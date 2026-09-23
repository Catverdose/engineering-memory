package com.engineeringmemory.knowledge.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import com.engineeringmemory.knowledge.entity.Document;
import com.engineeringmemory.knowledge.entity.Document.IndexingStatus;
import com.engineeringmemory.knowledge.enums.DocumentType;

public record DocumentSummaryResponse(
		Long id,
		String title,
		DocumentType documentType,
		String documentTypeLabel,
		List<String> projects,
		List<String> technologies,
		List<String> tags,
		String sourceName,
		String mediaType,
		LocalDate occurredOn,
		int version,
		String embeddingModel,
		IndexingStatus indexingStatus,
		boolean needsReindex,
		OffsetDateTime createdAt,
		OffsetDateTime updatedAt) {

	public static DocumentSummaryResponse from(Document document, String currentEmbeddingModel) {
		return new DocumentSummaryResponse(
				document.getId(),
				document.getTitle(),
				document.getDocumentType(),
				document.getDocumentType().getLabel(),
				List.copyOf(document.getProjects()),
				List.copyOf(document.getTechnologies()),
				List.copyOf(document.getTags()),
				document.getSourceName(),
				document.getMediaType(),
				document.getOccurredOn(),
				document.getVersion(),
				document.getEmbeddingModel(),
				document.getIndexingStatus(),
				document.getIndexingStatus() != IndexingStatus.READY
						|| !java.util.Objects.equals(document.getEmbeddingModel(), currentEmbeddingModel),
				document.getCreatedAt(),
				document.getUpdatedAt());
	}
}
