package com.engineeringmemory.knowledge.controller;

import static com.engineeringmemory.auth.security.OwnerContext.requireOwnerId;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.engineeringmemory.knowledge.dto.request.DocumentCreateRequest;
import com.engineeringmemory.knowledge.dto.request.DocumentMetadataRequest;
import com.engineeringmemory.knowledge.dto.response.DocumentDetailResponse;
import com.engineeringmemory.knowledge.dto.response.DocumentSummaryResponse;
import com.engineeringmemory.knowledge.dto.response.KnowledgeFacetsResponse;
import com.engineeringmemory.knowledge.entity.Document.IndexingStatus;
import com.engineeringmemory.knowledge.enums.DocumentType;
import com.engineeringmemory.knowledge.service.DocumentService;
import com.engineeringmemory.knowledge.service.DocumentService.OriginalDocument;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
@Validated
public class DocumentController {

	private final DocumentService documentService;

	@PostMapping(value = "/documents", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<DocumentDetailResponse> createText(
			Authentication authentication,
			@Valid @RequestBody DocumentCreateRequest request) {
		DocumentDetailResponse created = documentService.createText(requireOwnerId(authentication), request);
		return ResponseEntity.accepted()
				.location(URI.create("/api/knowledge/documents/" + created.id()))
				.cacheControl(CacheControl.noStore())
				.body(created);
	}

	@PostMapping(
			value = { "/documents", "/documents/upload" },
			consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<DocumentDetailResponse> createFile(
			Authentication authentication,
			@Valid @RequestPart("metadata") DocumentMetadataRequest metadata,
			@RequestPart("file") MultipartFile file) {
		DocumentDetailResponse created = documentService.createFile(requireOwnerId(authentication), metadata, file);
		return ResponseEntity.accepted()
				.location(URI.create("/api/knowledge/documents/" + created.id()))
				.cacheControl(CacheControl.noStore())
				.body(created);
	}

	@GetMapping("/documents")
	public ResponseEntity<Page<DocumentSummaryResponse>> list(
			Authentication authentication,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size,
			@RequestParam(required = false, name = "q") @Size(max = 200) String query,
			@RequestParam(required = false, name = "documentType")
			@Size(max = 20) List<DocumentType> documentTypes,
			@RequestParam(required = false, name = "project")
			@Size(max = 20) List<@NotBlank @Size(max = 100) String> projects,
			@RequestParam(required = false, name = "technology")
			@Size(max = 30) List<@NotBlank @Size(max = 100) String> technologies,
			@RequestParam(required = false, name = "tag")
			@Size(max = 50) List<@NotBlank @Size(max = 100) String> tags,
			@RequestParam(required = false, name = "indexingStatus")
			@Size(max = 3) List<IndexingStatus> indexingStatuses,
			@RequestParam(required = false) LocalDate from,
			@RequestParam(required = false) LocalDate to) {
		if (from != null && to != null && from.isAfter(to)) {
			throw new IllegalArgumentException("검색 기간의 시작일은 종료일보다 늦을 수 없습니다.");
		}
		Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)));
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(documentService.list(requireOwnerId(authentication), query, documentTypes,
						projects, technologies, tags, indexingStatuses, from, to, pageable));
	}

	@GetMapping("/documents/{documentId}")
	public ResponseEntity<DocumentDetailResponse> detail(
			Authentication authentication,
			@PathVariable long documentId) {
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(documentService.detail(requireOwnerId(authentication), documentId));
	}

	@PutMapping(value = "/documents/{documentId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<DocumentDetailResponse> updateText(
			Authentication authentication,
			@PathVariable long documentId,
			@Valid @RequestBody DocumentCreateRequest request) {
		return ResponseEntity.accepted()
				.cacheControl(CacheControl.noStore())
				.body(documentService.updateText(requireOwnerId(authentication), documentId, request));
	}

	@PatchMapping(value = "/documents/{documentId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<DocumentDetailResponse> updateMetadata(
			Authentication authentication,
			@PathVariable long documentId,
			@Valid @RequestBody DocumentMetadataRequest request) {
		return ResponseEntity.accepted()
				.cacheControl(CacheControl.noStore())
				.body(documentService.updateMetadata(requireOwnerId(authentication), documentId, request));
	}

	@DeleteMapping("/documents/{documentId}")
	public ResponseEntity<Void> delete(
			Authentication authentication,
			@PathVariable long documentId) {
		documentService.delete(requireOwnerId(authentication), documentId);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/documents/{documentId}/reindex")
	public ResponseEntity<DocumentDetailResponse> reindex(
			Authentication authentication,
			@PathVariable long documentId) {
		return ResponseEntity.accepted()
				.cacheControl(CacheControl.noStore())
				.body(documentService.reindex(requireOwnerId(authentication), documentId));
	}

	@GetMapping("/facets")
	public ResponseEntity<KnowledgeFacetsResponse> facets(Authentication authentication) {
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(documentService.facets(requireOwnerId(authentication)));
	}

	@GetMapping("/documents/{documentId}/original")
	public ResponseEntity<byte[]> original(
			Authentication authentication,
			@PathVariable long documentId) {
		OriginalDocument original = documentService.original(requireOwnerId(authentication), documentId);
		byte[] content = original.content();
		ContentDisposition disposition = ContentDisposition.attachment()
				.filename(original.filename(), StandardCharsets.UTF_8)
				.build();
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
				.contentType(MediaType.parseMediaType(original.mediaType()))
				.contentLength(content.length)
				.body(content);
	}
}
