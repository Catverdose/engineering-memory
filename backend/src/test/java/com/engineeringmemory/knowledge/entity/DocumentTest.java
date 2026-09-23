package com.engineeringmemory.knowledge.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.engineeringmemory.knowledge.entity.Document.IndexingStatus;
import com.engineeringmemory.knowledge.enums.DocumentType;

class DocumentTest {

	@Test
	void metadataUpdateKeepsOriginalAndCreatesPendingVersion() {
		byte[] original = "%PDF-test".getBytes(StandardCharsets.UTF_8);
		Document document = Document.create(
				7L, "old", DocumentType.DOCUMENT,
				List.of("old-project"), List.of("Java"), List.of("old-tag"),
				"design.pdf", "https://old.example", "application/pdf",
				LocalDate.of(2025, 1, 1), "extracted text", original, "hash");

		document.updateMetadata(
				"new", DocumentType.DECISION,
				List.of("new-project"), List.of("PostgreSQL"), List.of("new-tag"),
				"https://new.example", LocalDate.of(2026, 9, 22));

		assertThat(document.getTitle()).isEqualTo("new");
		assertThat(document.getDocumentType()).isEqualTo(DocumentType.DECISION);
		assertThat(document.getProjects()).containsExactly("new-project");
		assertThat(document.getSourceName()).isEqualTo("design.pdf");
		assertThat(document.getMediaType()).isEqualTo("application/pdf");
		assertThat(document.getContent()).isEqualTo("extracted text");
		assertThat(document.copyOriginalContent()).isEqualTo(original);
		assertThat(document.getVersion()).isEqualTo(2);
		assertThat(document.getIndexingStatus()).isEqualTo(IndexingStatus.PENDING);
	}

	@Test
	void textReplacementPreservesDeclaredTextFormat() {
		Document document = Document.create(
				7L, "README", DocumentType.PROJECT_DOC,
				List.of(), List.of(), List.of(),
				"README.md", null, "text/markdown;charset=UTF-8",
				null, "old", "old".getBytes(StandardCharsets.UTF_8), "old-hash");

		document.replaceText(
				"README", DocumentType.PROJECT_DOC,
				List.of(), List.of(), List.of(),
				"README.md", "text/markdown;charset=UTF-8", null, null,
				"new", "new".getBytes(StandardCharsets.UTF_8), "new-hash");

		assertThat(document.getSourceName()).isEqualTo("README.md");
		assertThat(document.getMediaType()).isEqualTo("text/markdown;charset=UTF-8");
		assertThat(document.getContent()).isEqualTo("new");
		assertThat(document.getVersion()).isEqualTo(2);
	}
}
