package com.engineeringmemory.conversation.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.engineeringmemory.common.repository.OwnerScopedRepository;
import com.engineeringmemory.conversation.entity.Conversation;

import jakarta.persistence.LockModeType;

public interface ConversationRepository extends OwnerScopedRepository<Conversation, Long> {

	Optional<Conversation> findByIdAndOwnerId(Long id, Long ownerId);

	Page<Conversation> findByOwnerId(Long ownerId, Pageable pageable);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Conversation c where c.id = :id and c.ownerId = :ownerId")
	Optional<Conversation> findLocked(@Param("id") Long id, @Param("ownerId") Long ownerId);
}
