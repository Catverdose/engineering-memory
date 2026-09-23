package com.engineeringmemory.knowledge.entity;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import com.engineeringmemory.knowledge.enums.DocumentType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "document")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Document {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "owner_id", nullable = false)
	private Long ownerId;

	@Column(nullable = false, length = 300)
	private String title;

	@Enumerated(EnumType.STRING)
	@Column(name = "document_type", nullable = false, length = 30)
	private DocumentType documentType;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb", nullable = false)
	private List<String> projects = new ArrayList<>();

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb", nullable = false)
	private List<String> technologies = new ArrayList<>();

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb", nullable = false)
	private List<String> tags = new ArrayList<>();

	@Column(name = "source_name", length = 300)
	private String sourceName;

	@Column(name = "source_uri", columnDefinition = "text")
	private String sourceUri;

	@Column(name = "media_type", nullable = false, length = 150)
	private String mediaType;

	@Column(name = "occurred_on")
	private LocalDate occurredOn;

	@Column(nullable = false, columnDefinition = "text")
	private String content;

	@Getter(AccessLevel.NONE)
	@Column(name = "original_content", nullable = false, columnDefinition = "bytea")
	private byte[] originalContent;

	@Column(name = "content_hash", nullable = false, length = 64)
	private String contentHash;

	@Column(nullable = false)
	private Integer version;

	@Version
	@Column(name = "lock_version", nullable = false)
	private Long lockVersion;

	@Column(name = "indexing_attempt", length = 36)
	private String indexingAttempt;

	@Column(name = "embedding_model", length = 100)
	private String embeddingModel;

	@Enumerated(EnumType.STRING)
	@Column(name = "indexing_status", nullable = false, length = 20)
	private IndexingStatus indexingStatus;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	public static Document create(
			long ownerId,
			String title,
			DocumentType documentType,
			List<String> projects,
			List<String> technologies,
			List<String> tags,
			String sourceName,
			String sourceUri,
			String mediaType,
			LocalDate occurredOn,
			String content,
			byte[] originalContent,
			String contentHash) {
		Document document = new Document();
		document.ownerId = ownerId;
		document.title = requireText(title, "title");
		document.documentType = Objects.requireNonNull(documentType, "documentType");
		document.projects = mutableCopy(projects);
		document.technologies = mutableCopy(technologies);
		document.tags = mutableCopy(tags);
		document.sourceName = requireText(sourceName, "sourceName");
		document.sourceUri = blankToNull(sourceUri);
		document.mediaType = requireText(mediaType, "mediaType");
		document.occurredOn = occurredOn;
		document.content = requireText(content, "content");
		document.originalContent = Objects.requireNonNull(originalContent, "originalContent").clone();
		document.contentHash = requireText(contentHash, "contentHash");
		document.version = 1;
		document.lockVersion = 0L;
		document.indexingAttempt = null;
		document.embeddingModel = null;
		document.indexingStatus = IndexingStatus.PENDING;
		return document;
	}

	public String beginIndexingAttempt() {
		this.indexingStatus = IndexingStatus.PENDING;
		this.indexingAttempt = UUID.randomUUID().toString();
		return this.indexingAttempt;
	}

	public void requestIndexing() {
		this.indexingStatus = IndexingStatus.PENDING;
		this.indexingAttempt = null;
	}

	public void markReady(String embeddingModel) {
		this.embeddingModel = requireText(embeddingModel, "embeddingModel");
		this.indexingStatus = IndexingStatus.READY;
		this.indexingAttempt = null;
	}

	public void markIndexingFailed() {
		this.indexingStatus = IndexingStatus.FAILED;
		this.indexingAttempt = null;
	}

	public boolean isCurrentIndexingAttempt(String attempt) {
		return Objects.equals(this.indexingAttempt, attempt);
	}

	public void replaceText(
			String title,
			DocumentType documentType,
			List<String> projects,
			List<String> technologies,
			List<String> tags,
			String sourceName,
			String mediaType,
			String sourceUri,
			LocalDate occurredOn,
			String content,
			byte[] originalContent,
			String contentHash) {
		this.title = requireText(title, "title");
		this.documentType = Objects.requireNonNull(documentType, "documentType");
		this.projects = mutableCopy(projects);
		this.technologies = mutableCopy(technologies);
		this.tags = mutableCopy(tags);
		this.sourceName = requireText(sourceName, "sourceName");
		this.sourceUri = blankToNull(sourceUri);
		this.mediaType = requireText(mediaType, "mediaType");
		this.occurredOn = occurredOn;
		this.content = requireText(content, "content");
		this.originalContent = Objects.requireNonNull(originalContent, "originalContent").clone();
		this.contentHash = requireText(contentHash, "contentHash");
		this.version++;
		this.indexingStatus = IndexingStatus.PENDING;
		this.indexingAttempt = null;
		this.embeddingModel = null;
	}

	public void updateMetadata(
			String title,
			DocumentType documentType,
			List<String> projects,
			List<String> technologies,
			List<String> tags,
			String sourceUri,
			LocalDate occurredOn) {
		this.title = requireText(title, "title");
		this.documentType = Objects.requireNonNull(documentType, "documentType");
		this.projects = mutableCopy(projects);
		this.technologies = mutableCopy(technologies);
		this.tags = mutableCopy(tags);
		this.sourceUri = blankToNull(sourceUri);
		this.occurredOn = occurredOn;
		this.version++;
		this.indexingStatus = IndexingStatus.PENDING;
		this.indexingAttempt = null;
		this.embeddingModel = null;
	}

	public byte[] copyOriginalContent() {
		return originalContent.clone();
	}

	private static List<String> mutableCopy(List<String> values) {
		return values == null ? new ArrayList<>() : new ArrayList<>(values);
	}

	private static String requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " 는 비어 있을 수 없습니다.");
		}
		return value;
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	public enum IndexingStatus {
		PENDING,
		READY,
		FAILED
	}
}
