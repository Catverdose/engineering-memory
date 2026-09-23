package com.engineeringmemory.conversation.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "conversation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Conversation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "owner_id", nullable = false)
	private Long ownerId;

	@Column(nullable = false, length = 160)
	private String title;

	@Column(name = "next_sequence", nullable = false)
	private Long nextSequence;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	public static Conversation create(long ownerId, String firstQuestion) {
		Conversation conversation = new Conversation();
		conversation.ownerId = ownerId;
		conversation.title = titleOf(firstQuestion);
		conversation.nextSequence = 1L;
		return conversation;
	}

	public long allocateSequence() {
		long allocated = nextSequence;
		nextSequence++;
		return allocated;
	}

	public void rename(String title) {
		if (title == null || title.isBlank()) {
			throw new IllegalArgumentException("대화 제목은 비어 있을 수 없습니다.");
		}
		this.title = title.strip().substring(0, Math.min(160, title.strip().length()));
	}

	private static String titleOf(String question) {
		String compact = question == null ? "새 대화" : question.replaceAll("\\s+", " ").strip();
		if (compact.isEmpty()) {
			return "새 대화";
		}
		return compact.substring(0, Math.min(80, compact.length()));
	}
}
