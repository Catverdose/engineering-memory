package com.engineeringmemory.conversation.entity;

import java.time.OffsetDateTime;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.engineeringmemory.chat.dto.response.ChatResponse.Source;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "chat_message")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {

	public enum Role { USER, ASSISTANT }

	public enum Status { COMPLETED, GENERATING, NO_CONTEXT, FAILED }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "conversation_id", nullable = false)
	private Long conversationId;

	@Column(name = "owner_id", nullable = false)
	private Long ownerId;

	@Column(name = "sequence_no", nullable = false)
	private Long sequence;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Role role;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Status status;

	@Column(nullable = false, columnDefinition = "text")
	private String content;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb", nullable = false)
	private List<Source> sources = List.of();

	@Column(name = "request_id", length = 80)
	private String requestId;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	public static ChatMessage user(long conversationId, long ownerId, long sequence, String content,
			String requestId) {
		ChatMessage message = new ChatMessage();
		message.conversationId = conversationId;
		message.ownerId = ownerId;
		message.sequence = sequence;
		message.role = Role.USER;
		message.status = Status.COMPLETED;
		message.content = content;
		message.sources = List.of();
		message.requestId = requestId;
		return message;
	}

	public static ChatMessage assistant(long conversationId, long ownerId, long sequence, String requestId) {
		ChatMessage message = new ChatMessage();
		message.conversationId = conversationId;
		message.ownerId = ownerId;
		message.sequence = sequence;
		message.role = Role.ASSISTANT;
		message.status = Status.GENERATING;
		message.content = "";
		message.sources = List.of();
		message.requestId = requestId;
		return message;
	}

	public void complete(String content, List<Source> sources, boolean noContext) {
		this.content = content == null ? "" : content;
		this.sources = sources == null ? List.of() : List.copyOf(sources);
		this.status = noContext ? Status.NO_CONTEXT : Status.COMPLETED;
	}

	public void fail() {
		this.status = Status.FAILED;
	}
}
