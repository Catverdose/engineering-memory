package com.engineeringmemory.conversation.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.engineeringmemory.chat.dto.response.ChatResponse.Source;
import com.engineeringmemory.common.exception.BusinessException;
import com.engineeringmemory.common.exception.ErrorCode;
import com.engineeringmemory.conversation.dto.ConversationResponse;
import com.engineeringmemory.conversation.entity.ChatMessage;
import com.engineeringmemory.conversation.entity.Conversation;
import com.engineeringmemory.conversation.repository.ChatMessageRepository;
import com.engineeringmemory.conversation.repository.ConversationRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ConversationService {

	private static final int HISTORY_MESSAGES = 8;

	private final ConversationRepository conversationRepository;
	private final ChatMessageRepository messageRepository;

	@Transactional
	public Turn beginTurn(long ownerId, Long requestedConversationId, String question, String requestId) {
		Conversation conversation;
		if (requestedConversationId == null) {
			conversation = conversationRepository.save(Conversation.create(ownerId, question));
		} else {
			conversation = conversationRepository.findLocked(requestedConversationId, ownerId)
					.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		}

		List<ChatMessage> newestFirst = messageRepository
				.findByConversationIdAndOwnerIdOrderBySequenceDesc(
						conversation.getId(), ownerId, PageRequest.of(0, HISTORY_MESSAGES));
		List<HistoryMessage> history = new ArrayList<>(newestFirst.stream()
				.filter(message -> message.getStatus() != ChatMessage.Status.GENERATING)
				.map(message -> new HistoryMessage(message.getRole().name(), message.getContent()))
				.toList());
		Collections.reverse(history);

		ChatMessage user = ChatMessage.user(conversation.getId(), ownerId,
				conversation.allocateSequence(), question, requestId);
		ChatMessage assistant = ChatMessage.assistant(conversation.getId(), ownerId,
				conversation.allocateSequence(), requestId);
		messageRepository.save(user);
		messageRepository.save(assistant);
		conversationRepository.save(conversation);

		return new Turn(conversation.getId(), assistant.getId(), List.copyOf(history));
	}

	@Transactional
	public void completeTurn(long ownerId, long assistantMessageId, String answer,
			List<Source> sources, boolean noContext) {
		ChatMessage message = messageRepository.findByIdAndOwnerId(assistantMessageId, ownerId)
				.orElseThrow(() -> new BusinessException(ErrorCode.INVARIANT_VIOLATION));
		message.complete(answer, sources, noContext);
	}

	@Transactional
	public void failTurn(long ownerId, long assistantMessageId) {
		messageRepository.findByIdAndOwnerId(assistantMessageId, ownerId).ifPresent(ChatMessage::fail);
	}

	@Transactional(readOnly = true)
	public Page<ConversationResponse> list(long ownerId, Pageable pageable) {
		Pageable ordered = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
				Sort.by(Sort.Direction.DESC, "updatedAt"));
		return conversationRepository.findByOwnerId(ownerId, ordered).map(ConversationResponse::summary);
	}

	@Transactional(readOnly = true)
	public ConversationResponse get(long ownerId, long conversationId, Long beforeSequence, int limit) {
		Conversation conversation = conversationRepository.findByIdAndOwnerId(conversationId, ownerId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		PageRequest page = PageRequest.of(0, limit + 1);
		List<ChatMessage> newestFirst = beforeSequence == null
				? messageRepository.findByConversationIdAndOwnerIdOrderBySequenceDesc(
						conversationId, ownerId, page)
				: messageRepository.findByConversationIdAndOwnerIdAndSequenceLessThanOrderBySequenceDesc(
						conversationId, ownerId, beforeSequence, page);
		boolean hasMore = newestFirst.size() > limit;
		List<ChatMessage> selected = new ArrayList<>(
				newestFirst.subList(0, Math.min(limit, newestFirst.size())));
		Long nextBefore = hasMore && !selected.isEmpty()
				? selected.get(selected.size() - 1).getSequence()
				: null;
		Collections.reverse(selected);
		return ConversationResponse.detail(conversation, selected, hasMore, nextBefore);
	}

	@Transactional
	public ConversationResponse rename(long ownerId, long conversationId, String title) {
		Conversation conversation = conversationRepository.findLocked(conversationId, ownerId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		conversation.rename(title);
		return ConversationResponse.summary(conversation);
	}

	@Transactional
	public void delete(long ownerId, long conversationId) {
		Conversation conversation = conversationRepository.findLocked(conversationId, ownerId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		messageRepository.deleteByConversationIdAndOwnerId(conversationId, ownerId);
		conversationRepository.delete(conversation);
	}

	public record Turn(Long conversationId, Long assistantMessageId, List<HistoryMessage> history) {
	}

	public record HistoryMessage(String role, String content) {
	}
}
