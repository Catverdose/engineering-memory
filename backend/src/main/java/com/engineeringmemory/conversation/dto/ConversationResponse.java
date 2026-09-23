package com.engineeringmemory.conversation.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.engineeringmemory.chat.dto.response.ChatResponse.Source;
import com.engineeringmemory.conversation.entity.ChatMessage;
import com.engineeringmemory.conversation.entity.Conversation;

public record ConversationResponse(
		Long id,
		String title,
		OffsetDateTime createdAt,
		OffsetDateTime updatedAt,
		List<Message> messages,
		boolean hasMoreMessages,
		Long nextBeforeSequence) {

	public static ConversationResponse summary(Conversation conversation) {
		return new ConversationResponse(conversation.getId(), conversation.getTitle(),
				conversation.getCreatedAt(), conversation.getUpdatedAt(), List.of(), false, null);
	}

	public static ConversationResponse detail(Conversation conversation, List<ChatMessage> messages,
			boolean hasMoreMessages, Long nextBeforeSequence) {
		return new ConversationResponse(conversation.getId(), conversation.getTitle(),
				conversation.getCreatedAt(), conversation.getUpdatedAt(),
				messages.stream().map(Message::from).toList(), hasMoreMessages, nextBeforeSequence);
	}

	public record Message(
			Long id,
			long sequence,
			String role,
			String status,
			String content,
			List<Source> sources,
			OffsetDateTime createdAt) {

		static Message from(ChatMessage message) {
			return new Message(message.getId(), message.getSequence(), message.getRole().name(),
					message.getStatus().name(), message.getContent(), message.getSources(),
					message.getCreatedAt());
		}
	}
}
