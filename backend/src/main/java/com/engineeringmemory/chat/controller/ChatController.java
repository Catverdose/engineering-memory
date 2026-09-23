package com.engineeringmemory.chat.controller;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.engineeringmemory.auth.security.OwnerContext;
import com.engineeringmemory.chat.dto.request.ChatRequest;
import com.engineeringmemory.chat.dto.response.ChatResponse;
import com.engineeringmemory.chat.service.ChatService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

	private final ChatService chatService;

	@PostMapping("/messages")
	public ChatResponse sendMessage(@Valid @RequestBody ChatRequest request,
			Authentication authentication) {
		return chatService.chat(OwnerContext.requireOwnerId(authentication), request);
	}

	@PostMapping(value = "/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter streamMessage(@Valid @RequestBody ChatRequest request,
			Authentication authentication) {
		return chatService.chatStream(OwnerContext.requireOwnerId(authentication), request);
	}
}
