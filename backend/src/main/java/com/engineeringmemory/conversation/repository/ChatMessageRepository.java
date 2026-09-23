package com.engineeringmemory.conversation.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import com.engineeringmemory.common.repository.OwnerScopedRepository;
import com.engineeringmemory.conversation.entity.ChatMessage;

public interface ChatMessageRepository extends OwnerScopedRepository<ChatMessage, Long> {

	Optional<ChatMessage> findByIdAndOwnerId(Long id, Long ownerId);

	List<ChatMessage> findByConversationIdAndOwnerIdOrderBySequenceDesc(
			Long conversationId, Long ownerId, Pageable pageable);

	List<ChatMessage> findByConversationIdAndOwnerIdAndSequenceLessThanOrderBySequenceDesc(
			Long conversationId, Long ownerId, Long sequence, Pageable pageable);

	@Modifying
	@Query("update ChatMessage m set m.status = com.engineeringmemory.conversation.entity.ChatMessage.Status.FAILED "
			+ "where m.status = com.engineeringmemory.conversation.entity.ChatMessage.Status.GENERATING")
	int failInterruptedGeneratingMessages();

	void deleteByConversationIdAndOwnerId(Long conversationId, Long ownerId);
}
