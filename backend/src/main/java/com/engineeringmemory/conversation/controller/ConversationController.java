package com.engineeringmemory.conversation.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.engineeringmemory.auth.security.OwnerContext;
import com.engineeringmemory.conversation.dto.ConversationRenameRequest;
import com.engineeringmemory.conversation.dto.ConversationResponse;
import com.engineeringmemory.conversation.service.ConversationService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

	private final ConversationService conversationService;

	@GetMapping
	public ResponseEntity<Page<ConversationResponse>> list(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size,
			Authentication authentication) {
		int safeSize = Math.max(1, Math.min(size, 100));
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(conversationService.list(OwnerContext.requireOwnerId(authentication),
						PageRequest.of(Math.max(0, page), safeSize)));
	}

	@GetMapping("/{id}")
	public ResponseEntity<ConversationResponse> get(
			@PathVariable long id,
			@RequestParam(required = false) Long beforeSequence,
			@RequestParam(defaultValue = "100") int limit,
			Authentication authentication) {
		if (beforeSequence != null && beforeSequence < 1) {
			throw new IllegalArgumentException("beforeSequence 는 1 이상이어야 합니다.");
		}
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(conversationService.get(OwnerContext.requireOwnerId(authentication), id,
						beforeSequence, Math.max(1, Math.min(limit, 200))));
	}

	@PatchMapping("/{id}")
	public ResponseEntity<ConversationResponse> rename(@PathVariable long id,
			@Valid @RequestBody ConversationRenameRequest request,
			Authentication authentication) {
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(conversationService.rename(
						OwnerContext.requireOwnerId(authentication), id, request.title()));
	}

	@DeleteMapping("/{id}")
	public void delete(@PathVariable long id, Authentication authentication) {
		conversationService.delete(OwnerContext.requireOwnerId(authentication), id);
	}
}
