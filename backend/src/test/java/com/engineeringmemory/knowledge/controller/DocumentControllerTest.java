package com.engineeringmemory.knowledge.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import com.engineeringmemory.auth.service.AssistantPrincipal;
import com.engineeringmemory.knowledge.dto.request.DocumentCreateRequest;
import com.engineeringmemory.knowledge.dto.request.DocumentMetadataRequest;
import com.engineeringmemory.knowledge.dto.response.DocumentDetailResponse;
import com.engineeringmemory.knowledge.entity.Document.IndexingStatus;
import com.engineeringmemory.knowledge.enums.DocumentType;
import com.engineeringmemory.knowledge.service.DocumentService;

class DocumentControllerTest {

	private final DocumentService documentService = mock(DocumentService.class);
	private final Authentication authentication = mock(Authentication.class);
	private final AssistantPrincipal principal = mock(AssistantPrincipal.class);
	private final DocumentController controller = new DocumentController(documentService);
	private final DocumentCreateRequest request = new DocumentCreateRequest(
			"title", DocumentType.NOTE, List.of(), List.of(), List.of(), null, null, "content");
	private final DocumentMetadataRequest metadata = request.metadata();
	private final DocumentDetailResponse pending = new DocumentDetailResponse(
			101L, "title", DocumentType.NOTE, "개발·학습 노트", List.of(), List.of(), List.of(),
			"title.txt", null, "text/plain;charset=UTF-8", null, "content", 1, null,
			IndexingStatus.PENDING, true, null, null);

	@BeforeEach
	void authenticate() {
		when(authentication.isAuthenticated()).thenReturn(true);
		when(authentication.getPrincipal()).thenReturn(principal);
		when(principal.isEnabled()).thenReturn(true);
		when(principal.ownerId()).thenReturn(7L);
	}

	@Test
	@DisplayName("생성은 Location과 PENDING 본문을 담은 202를 반환한다")
	void createReturnsAccepted() {
		when(documentService.createText(7L, request)).thenReturn(pending);

		ResponseEntity<DocumentDetailResponse> response = controller.createText(authentication, request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(response.getHeaders().getLocation()).hasPath("/api/knowledge/documents/101");
		assertThat(response.getBody()).isEqualTo(pending);
	}

	@Test
	@DisplayName("수정과 재색인은 완료를 기다리지 않고 202를 반환한다")
	void updateAndReindexReturnAccepted() {
		when(documentService.updateText(7L, 101L, request)).thenReturn(pending);
		when(documentService.reindex(7L, 101L)).thenReturn(pending);
		when(documentService.updateMetadata(7L, 101L, metadata)).thenReturn(pending);

		assertThat(controller.updateText(authentication, 101L, request).getStatusCode())
				.isEqualTo(HttpStatus.ACCEPTED);
		assertThat(controller.reindex(authentication, 101L).getStatusCode())
				.isEqualTo(HttpStatus.ACCEPTED);
		assertThat(controller.updateMetadata(authentication, 101L, metadata).getStatusCode())
				.isEqualTo(HttpStatus.ACCEPTED);
		verify(documentService).updateText(7L, 101L, request);
		verify(documentService).updateMetadata(7L, 101L, metadata);
		verify(documentService).reindex(7L, 101L);
	}
}
