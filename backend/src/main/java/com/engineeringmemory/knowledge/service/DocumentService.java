package com.engineeringmemory.knowledge.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.engineeringmemory.common.exception.BusinessException;
import com.engineeringmemory.common.exception.ErrorCode;
import com.engineeringmemory.embedding.service.EmbeddingService;
import com.engineeringmemory.knowledge.dto.request.DocumentCreateRequest;
import com.engineeringmemory.knowledge.dto.request.DocumentMetadataRequest;
import com.engineeringmemory.knowledge.dto.response.DocumentDetailResponse;
import com.engineeringmemory.knowledge.dto.response.DocumentSummaryResponse;
import com.engineeringmemory.knowledge.dto.response.KnowledgeFacetsResponse;
import com.engineeringmemory.knowledge.dto.response.KnowledgeFacetsResponse.DocumentTypeFacet;
import com.engineeringmemory.knowledge.entity.Document;
import com.engineeringmemory.knowledge.enums.DocumentType;
import com.engineeringmemory.knowledge.repository.DocumentRepository;
import com.engineeringmemory.knowledge.repository.DocumentListRepository;

@Service
public class DocumentService {

	private final DocumentRepository documentRepository;
	private final DocumentListRepository documentListRepository;
	private final KnowledgeFileExtractor fileExtractor;
	private final DocumentIndexingService indexingService;
	private final EmbeddingService embeddingService;

	public DocumentService(
			DocumentRepository documentRepository,
			DocumentListRepository documentListRepository,
			KnowledgeFileExtractor fileExtractor,
			DocumentIndexingService indexingService,
			EmbeddingService embeddingService) {
		this.documentRepository = documentRepository;
		this.documentListRepository = documentListRepository;
		this.fileExtractor = fileExtractor;
		this.indexingService = indexingService;
		this.embeddingService = embeddingService;
	}

	public DocumentDetailResponse createText(long ownerId, DocumentCreateRequest request) {
		String content = fileExtractor.validateText(request.content());
		byte[] original = content.getBytes(StandardCharsets.UTF_8);
		String sourceName = textFilename(request.title());
		return createAndIndex(
				ownerId,
				request.metadata(),
				new ExtractedDocument(content, original, sourceName, "text/plain;charset=UTF-8"));
	}

	public DocumentDetailResponse createFile(
			long ownerId, DocumentMetadataRequest metadata, MultipartFile file) {
		return createAndIndex(ownerId, metadata, fileExtractor.extract(file));
	}

	public DocumentDetailResponse updateText(long ownerId, long documentId, DocumentCreateRequest request) {
		String content = fileExtractor.validateText(request.content());
		byte[] original = content.getBytes(StandardCharsets.UTF_8);
		String contentHash = sha256(original);
		if (documentRepository.existsByOwnerIdAndContentHashAndIdNot(ownerId, contentHash, documentId)) {
			throw new BusinessException(ErrorCode.DUPLICATE_DOCUMENT);
		}

		Document document = getOwned(ownerId, documentId);
		if ("application/pdf".equalsIgnoreCase(document.getMediaType())) {
			throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE,
					"PDF 본문은 텍스트 수정으로 교체할 수 없습니다. 메타데이터 수정 또는 새 파일 등록을 사용해 주세요.");
		}
		document.replaceText(
				request.title().strip(), request.documentType(),
				normalizeLabels(request.projects()), normalizeLabels(request.technologies()),
				normalizeLabels(request.tags()), document.getSourceName(), document.getMediaType(),
				nullIfBlank(request.sourceUri()), request.occurredOn(), content, original, contentHash);
		try {
			documentRepository.saveAndFlush(document);
		} catch (OptimisticLockingFailureException e) {
			throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,
					"다른 요청이 문서를 먼저 수정했습니다. 최신 문서를 다시 확인해 주세요.", e);
		} catch (DataIntegrityViolationException e) {
			if (documentRepository.existsByOwnerIdAndContentHashAndIdNot(ownerId, contentHash, documentId)) {
				throw new BusinessException(ErrorCode.DUPLICATE_DOCUMENT,
						"같은 내용의 문서가 이미 등록되어 있습니다.", e);
			}
			throw e;
		}

		indexingService.requestIndex(ownerId, documentId);
		return detail(ownerId, documentId);
	}

	public DocumentDetailResponse updateMetadata(
			long ownerId, long documentId, DocumentMetadataRequest request) {
		Document document = getOwned(ownerId, documentId);
		document.updateMetadata(
				request.title().strip(), request.documentType(),
				normalizeLabels(request.projects()), normalizeLabels(request.technologies()),
				normalizeLabels(request.tags()), nullIfBlank(request.sourceUri()), request.occurredOn());
		try {
			documentRepository.saveAndFlush(document);
		} catch (OptimisticLockingFailureException e) {
			throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,
					"다른 요청이 문서를 먼저 수정했습니다. 최신 문서를 다시 확인해 주세요.", e);
		}
		indexingService.requestIndex(ownerId, documentId);
		return detail(ownerId, documentId);
	}

	@Transactional(readOnly = true)
	public Page<DocumentSummaryResponse> list(long ownerId, String query,
			List<DocumentType> documentTypes, List<String> projects, List<String> technologies,
			List<String> tags, List<Document.IndexingStatus> indexingStatuses,
			java.time.LocalDate from, java.time.LocalDate to, Pageable pageable) {
		return documentListRepository.search(ownerId, query, documentTypes, projects,
				technologies, tags, indexingStatuses, from, to,
				embeddingService.embeddingModel(), pageable);
	}

	@Transactional(readOnly = true)
	public DocumentDetailResponse detail(long ownerId, long documentId) {
		return DocumentDetailResponse.from(
				getOwned(ownerId, documentId), embeddingService.embeddingModel());
	}

	@Transactional
	public void delete(long ownerId, long documentId) {
		documentRepository.delete(getOwned(ownerId, documentId));
	}

	public DocumentDetailResponse reindex(long ownerId, long documentId) {
		indexingService.requestIndex(ownerId, documentId);
		return detail(ownerId, documentId);
	}

	@Transactional(readOnly = true)
	public KnowledgeFacetsResponse facets(long ownerId) {
		Map<DocumentType, Long> counts = new HashMap<>();
		for (Object[] row : documentRepository.countDocumentTypes(ownerId)) {
			DocumentType type = row[0] instanceof DocumentType documentType
					? documentType
					: DocumentType.valueOf(row[0].toString());
			counts.put(type, ((Number) row[1]).longValue());
		}
		List<DocumentTypeFacet> types = java.util.Arrays.stream(DocumentType.values())
				.map(type -> new DocumentTypeFacet(type.name(), type.getLabel(), counts.getOrDefault(type, 0L)))
				.toList();
		return new KnowledgeFacetsResponse(
				types,
				documentRepository.findDistinctProjects(ownerId),
				documentRepository.findDistinctTechnologies(ownerId),
				documentRepository.findDistinctTags(ownerId));
	}

	@Transactional(readOnly = true)
	public OriginalDocument original(long ownerId, long documentId) {
		Document document = getOwned(ownerId, documentId);
		return new OriginalDocument(
				document.getSourceName(), document.getMediaType(), document.copyOriginalContent());
	}

	private DocumentDetailResponse createAndIndex(
			long ownerId, DocumentMetadataRequest metadata, ExtractedDocument extracted) {
		byte[] original = extracted.originalBytes();
		String contentHash = sha256(original);
		if (documentRepository.existsByOwnerIdAndContentHash(ownerId, contentHash)) {
			throw new BusinessException(ErrorCode.DUPLICATE_DOCUMENT,
					"같은 내용의 문서가 이미 등록되어 있습니다.");
		}

		Document document = Document.create(
				ownerId,
				metadata.title().strip(),
				metadata.documentType(),
				normalizeLabels(metadata.projects()),
				normalizeLabels(metadata.technologies()),
				normalizeLabels(metadata.tags()),
				extracted.sourceName(),
				nullIfBlank(metadata.sourceUri()),
				extracted.mediaType(),
				metadata.occurredOn(),
				extracted.content(),
				original,
				contentHash);
		try {
			document = documentRepository.saveAndFlush(document);
		} catch (DataIntegrityViolationException e) {
			if (documentRepository.existsByOwnerIdAndContentHash(ownerId, contentHash)) {
				throw new BusinessException(ErrorCode.DUPLICATE_DOCUMENT,
						"같은 내용의 문서가 이미 등록되어 있습니다.", e);
			}
			throw e;
		}

		indexingService.requestIndex(ownerId, document.getId());
		return detail(ownerId, document.getId());
	}

	private Document getOwned(long ownerId, long documentId) {
		return documentRepository.findByIdAndOwnerId(documentId, ownerId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
	}

	private static List<String> normalizeLabels(List<String> values) {
		if (values == null || values.isEmpty()) {
			return List.of();
		}
		Set<String> seen = new HashSet<>();
		List<String> normalized = new ArrayList<>();
		for (String value : values) {
			if (value == null || value.isBlank()) {
				continue;
			}
			String trimmed = value.strip();
			if (seen.add(trimmed.toLowerCase(Locale.ROOT))) {
				normalized.add(trimmed);
			}
		}
		return List.copyOf(normalized);
	}

	private static String textFilename(String title) {
		String safe = title.replace('\\', '_').replace('/', '_')
				.replaceAll("[\\p{Cntrl}]", "")
				.strip();
		if (safe.isBlank()) {
			safe = "document";
		}
		int maxBase = 296;
		if (safe.length() > maxBase) {
			safe = safe.substring(0, maxBase);
		}
		return safe + ".txt";
	}

	private static String nullIfBlank(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("JVM이 SHA-256을 지원하지 않습니다.", e);
		}
	}

	public record OriginalDocument(String filename, String mediaType, byte[] content) {
	}
}
