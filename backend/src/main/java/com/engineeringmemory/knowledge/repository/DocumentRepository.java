package com.engineeringmemory.knowledge.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;

import com.engineeringmemory.common.repository.OwnerScopedRepository;
import com.engineeringmemory.knowledge.entity.Document;

import jakarta.persistence.LockModeType;

public interface DocumentRepository extends OwnerScopedRepository<Document, Long> {

	interface PendingDocumentReference {
		Long getId();

		Long getOwnerId();
	}

	Optional<Document> findByIdAndOwnerId(Long id, Long ownerId);

	Optional<Document> findByIdAndOwnerIdIsNull(Long id);

	List<Document> findByOwnerIdIsNull();

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select d from Document d where d.id = :id and d.ownerId = :ownerId")
	Optional<Document> findLockedByIdAndOwnerId(@Param("id") Long id, @Param("ownerId") Long ownerId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select d from Document d where d.id = :id and d.ownerId is null")
	Optional<Document> findLockedSharedById(@Param("id") Long id);

	boolean existsByOwnerIdAndContentHash(Long ownerId, String contentHash);

	boolean existsByOwnerIdAndContentHashAndIdNot(Long ownerId, String contentHash, Long id);

	@Query("""
			select d.id as id, d.ownerId as ownerId
			  from Document d
			 where d.indexingStatus = com.engineeringmemory.knowledge.entity.Document.IndexingStatus.PENDING
			 order by d.id
			""")
	List<PendingDocumentReference> findPendingForIndexing(Pageable pageable);

	@Query("""
			select d.documentType, count(d)
			  from Document d
			 where d.ownerId = :ownerId or d.ownerId is null
			 group by d.documentType
			""")
	List<Object[]> countVisibleDocumentTypes(@Param("ownerId") Long ownerId);

	@Query(value = """
			select distinct p.value
			  from document d
			 cross join lateral jsonb_array_elements_text(d.projects) p(value)
			 where d.owner_id = :ownerId or d.owner_id is null
			 order by p.value
			""", nativeQuery = true)
	List<String> findVisibleProjects(@Param("ownerId") Long ownerId);

	@Query(value = """
			select distinct t.value
			  from document d
			 cross join lateral jsonb_array_elements_text(d.technologies) t(value)
			 where d.owner_id = :ownerId or d.owner_id is null
			 order by t.value
			""", nativeQuery = true)
	List<String> findVisibleTechnologies(@Param("ownerId") Long ownerId);

	@Query(value = """
			select distinct t.value
			  from document d
			 cross join lateral jsonb_array_elements_text(d.tags) t(value)
			 where d.owner_id = :ownerId or d.owner_id is null
			 order by t.value
			""", nativeQuery = true)
	List<String> findVisibleTags(@Param("ownerId") Long ownerId);
}
