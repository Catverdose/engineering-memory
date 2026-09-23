package com.engineeringmemory.knowledge.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "document_chunk")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentChunk {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "document_id", nullable = false)
	private Long documentId;

	@Column(name = "owner_id", nullable = false)
	private Long ownerId;

	@Column(name = "document_version", nullable = false)
	private Integer documentVersion;

	@Column(name = "chunk_index", nullable = false)
	private Integer chunkIndex;

	@Column(length = 300)
	private String heading;

	@Column(nullable = false, columnDefinition = "text")
	private String content;

	@Column(name = "char_count", nullable = false)
	private Integer charCount;

	@Column(name = "embedding_model", nullable = false, length = 100)
	private String embeddingModel;

	public static DocumentChunk create(
			long documentId,
			long ownerId,
			int documentVersion,
			int chunkIndex,
			String heading,
			String content,
			String embeddingModel) {
		if (documentVersion < 1) {
			throw new IllegalArgumentException("documentVersion 은 1 이상이어야 합니다.");
		}
		if (chunkIndex < 0) {
			throw new IllegalArgumentException("chunkIndex 는 0 이상이어야 합니다.");
		}
		if (content == null || content.isBlank()) {
			throw new IllegalArgumentException("청크 본문은 비어 있을 수 없습니다.");
		}
		if (embeddingModel == null || embeddingModel.isBlank()) {
			throw new IllegalArgumentException("embeddingModel 은 비어 있을 수 없습니다.");
		}
		DocumentChunk chunk = new DocumentChunk();
		chunk.documentId = documentId;
		chunk.ownerId = ownerId;
		chunk.documentVersion = documentVersion;
		chunk.chunkIndex = chunkIndex;
		chunk.heading = heading == null || heading.isBlank() ? null : heading;
		chunk.content = content;
		chunk.charCount = content.length();
		chunk.embeddingModel = embeddingModel;
		return chunk;
	}
}
