package com.engineeringmemory.knowledge.repository;

import com.engineeringmemory.common.repository.OwnerScopedRepository;
import com.engineeringmemory.knowledge.entity.DocumentChunk;

public interface DocumentChunkRepository extends OwnerScopedRepository<DocumentChunk, Long> {

	long deleteByDocumentIdAndOwnerId(Long documentId, Long ownerId);
}
