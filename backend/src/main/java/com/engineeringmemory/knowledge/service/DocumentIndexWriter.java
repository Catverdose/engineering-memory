package com.engineeringmemory.knowledge.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.engineeringmemory.common.exception.BusinessException;
import com.engineeringmemory.common.exception.ErrorCode;
import com.engineeringmemory.knowledge.entity.Document;
import com.engineeringmemory.knowledge.entity.Document.IndexingStatus;
import com.engineeringmemory.knowledge.entity.DocumentChunk;
import com.engineeringmemory.knowledge.enums.DocumentType;
import com.engineeringmemory.knowledge.repository.DocumentChunkRepository;
import com.engineeringmemory.knowledge.repository.DocumentChunkVectorRepository;
import com.engineeringmemory.knowledge.repository.DocumentRepository;

@Service
public class DocumentIndexWriter {

	private final DocumentRepository documentRepository;
	private final DocumentChunkRepository chunkRepository;
	private final DocumentChunkVectorRepository vectorRepository;

	public DocumentIndexWriter(
			DocumentRepository documentRepository,
			DocumentChunkRepository chunkRepository,
			DocumentChunkVectorRepository vectorRepository) {
		this.documentRepository = documentRepository;
		this.chunkRepository = chunkRepository;
		this.vectorRepository = vectorRepository;
	}

	@Transactional
	public IndexSource prepare(Long ownerId, long documentId) {
		Document document = findLocked(ownerId, documentId);
		if (document.getIndexingStatus() != IndexingStatus.PENDING) {
			throw new BusinessException(ErrorCode.INVARIANT_VIOLATION,
					"색인 대기 상태가 아닌 문서입니다. documentId=" + documentId);
		}
		String attempt = document.beginIndexingAttempt();
		return new IndexSource(
				document.getId(),
				document.getOwnerId(),
				document.getVersion(),
				attempt,
				document.getTitle(),
				document.getDocumentType(),
				List.copyOf(document.getProjects()),
				List.copyOf(document.getTechnologies()),
				List.copyOf(document.getTags()),
				document.getContent());
	}

	@Transactional
	public void requestIndexing(Long ownerId, long documentId) {
		findLocked(ownerId, documentId).requestIndexing();
	}

	@Transactional
	public void replaceAndMarkReady(IndexSource source, List<IndexedChunk> chunks, String embeddingModel) {
		Document document = getOwned(source.ownerId(), source.documentId());
		if (!document.getVersion().equals(source.documentVersion())
				|| document.getIndexingStatus() != IndexingStatus.PENDING
				|| !document.isCurrentIndexingAttempt(source.indexingAttempt())) {
			throw new BusinessException(ErrorCode.INVARIANT_VIOLATION,
					"색인 중 문서 버전이나 상태가 바뀌었습니다. documentId=" + source.documentId());
		}
		if (chunks.isEmpty()) {
			throw new BusinessException(ErrorCode.INVARIANT_VIOLATION, "저장할 문서 청크가 없습니다.");
		}

		if (source.ownerId() == null) {
			chunkRepository.deleteByDocumentIdAndOwnerIdIsNull(source.documentId());
		} else {
			chunkRepository.deleteByDocumentIdAndOwnerId(source.documentId(), source.ownerId());
		}
		chunkRepository.flush();

		for (IndexedChunk indexed : chunks) {
			DocumentChunk chunk = chunkRepository.save(DocumentChunk.create(
					source.documentId(),
					source.ownerId(),
					source.documentVersion(),
					indexed.index(),
					indexed.heading(),
					indexed.content(),
					embeddingModel));
			vectorRepository.updateEmbedding(
					chunk.getId(), source.ownerId(), source.documentVersion(), indexed.embedding(), embeddingModel);
		}
		document.markReady(embeddingModel);
	}

	@Transactional
	public void markFailed(IndexSource source) {
		find(source.ownerId(), source.documentId())
				.filter(document -> document.getVersion().equals(source.documentVersion()))
				.filter(document -> document.getIndexingStatus() == IndexingStatus.PENDING)
				.filter(document -> document.isCurrentIndexingAttempt(source.indexingAttempt()))
				.ifPresent(Document::markIndexingFailed);
	}

	private Document getOwned(Long ownerId, long documentId) {
		return find(ownerId, documentId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
	}

	private Optional<Document> find(Long ownerId, long documentId) {
		return ownerId == null
				? documentRepository.findByIdAndOwnerIdIsNull(documentId)
				: documentRepository.findByIdAndOwnerId(documentId, ownerId);
	}

	private Document findLocked(Long ownerId, long documentId) {
		return (ownerId == null
				? documentRepository.findLockedSharedById(documentId)
				: documentRepository.findLockedByIdAndOwnerId(documentId, ownerId))
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
	}

	public record IndexSource(
			long documentId,
			Long ownerId,
			int documentVersion,
			String indexingAttempt,
			String title,
			DocumentType documentType,
			List<String> projects,
			List<String> technologies,
			List<String> tags,
			String content) {
	}

	public record IndexedChunk(
			int index,
			String heading,
			String content,
			float[] embedding) {

		public IndexedChunk {
			embedding = embedding.clone();
		}

		@Override
		public float[] embedding() {
			return embedding.clone();
		}
	}
}
