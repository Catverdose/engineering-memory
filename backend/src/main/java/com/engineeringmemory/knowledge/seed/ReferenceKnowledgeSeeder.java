package com.engineeringmemory.knowledge.seed;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.engineeringmemory.embedding.service.EmbeddingService;
import com.engineeringmemory.knowledge.config.KnowledgeSeedProperties;
import com.engineeringmemory.knowledge.dto.request.DocumentCreateRequest;
import com.engineeringmemory.knowledge.entity.Document;
import com.engineeringmemory.knowledge.entity.Document.IndexingStatus;
import com.engineeringmemory.knowledge.repository.DocumentRepository;
import com.engineeringmemory.knowledge.service.DocumentService;
import com.engineeringmemory.llm.service.LlmService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ReferenceKnowledgeSeeder {

	private final KnowledgeSeedProperties properties;
	private final ReferenceKnowledgeCatalog catalog;
	private final DocumentRepository documentRepository;
	private final DocumentService documentService;
	private final EmbeddingService embeddingService;
	private final LlmService llmService;

	private boolean finished;
	private boolean waitingLogged;

	public ReferenceKnowledgeSeeder(
			KnowledgeSeedProperties properties,
			ReferenceKnowledgeCatalog catalog,
			DocumentRepository documentRepository,
			DocumentService documentService,
			EmbeddingService embeddingService,
			LlmService llmService) {
		this.properties = properties;
		this.catalog = catalog;
		this.documentRepository = documentRepository;
		this.documentService = documentService;
		this.embeddingService = embeddingService;
		this.llmService = llmService;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void syncOnStartup() {
		trySync();
	}

	@Scheduled(fixedDelayString = "${knowledge.seed.retry-delay:30s}",
			initialDelayString = "${knowledge.seed.retry-delay:30s}")
	void retryUntilSynced() {
		trySync();
	}

	synchronized void trySync() {
		if (finished) {
			return;
		}
		if (!properties.enabled()) {
			log.info("공용 기본 지식 동기화를 끕니다(knowledge.seed.enabled=false).");
			finished = true;
			return;
		}
		if (!embeddingReady()) {
			if (!waitingLogged) {
				log.info("임베딩 모델이 준비되지 않아 공용 기본 지식 동기화를 미룹니다. {} 뒤 다시 확인합니다.",
						properties.retryDelay());
				waitingLogged = true;
			}
			return;
		}

		try {
			SyncResult result = sync(catalog.load());
			log.info("공용 기본 지식 동기화: 등록={}, 갱신={}, 재색인={}, 삭제={}, 유지={}, 실패={}",
					result.created, result.updated, result.reindexed, result.removed, result.kept, result.failed);
		} catch (DataAccessException e) {
			log.error("공용 기본 지식 동기화 실패. DB 스키마가 공용 문서(owner_id NULL)를 지원하는지 확인하세요 "
					+ "(backend/src/main/resources/db/schema.sql 재적용): {}", e.getMostSpecificCause().getMessage());
		} catch (RuntimeException e) {
			log.error("공용 기본 지식 동기화 실패", e);
		}
		finished = true;
	}

	SyncResult sync(List<DocumentCreateRequest> entries) {
		SyncResult result = new SyncResult();
		Map<String, Document> existing = new HashMap<>();
		for (Document document : documentRepository.findByOwnerIdIsNull()) {
			existing.put(document.getTitle(), document);
		}
		String currentModel = embeddingService.embeddingModel();

		for (DocumentCreateRequest entry : entries) {
			Document document = existing.remove(entry.title());
			try {
				if (document == null) {
					documentService.createShared(entry);
					result.created++;
				} else if (!documentService.sharedMatches(document, entry)) {
					documentService.replaceShared(document.getId(), entry);
					result.updated++;
				} else if (needsReindex(document, currentModel)) {
					documentService.reindexShared(document.getId());
					result.reindexed++;
				} else {
					result.kept++;
				}
			} catch (DataAccessException e) {
				throw e;
			} catch (RuntimeException e) {
				result.failed++;
				log.warn("공용 기본 지식 반영 실패: title={}, cause={}", entry.title(), e.toString());
			}
		}

		for (Document orphan : existing.values()) {
			documentService.deleteShared(orphan.getId());
			result.removed++;
		}
		return result;
	}

	private static boolean needsReindex(Document document, String currentModel) {
		return document.getIndexingStatus() == IndexingStatus.FAILED
				|| document.getIndexingStatus() == IndexingStatus.READY
						&& !Objects.equals(document.getEmbeddingModel(), currentModel);
	}

	private boolean embeddingReady() {
		try {
			return llmService.isEmbeddingAvailable();
		} catch (RuntimeException e) {
			return false;
		}
	}

	static final class SyncResult {
		int created;
		int updated;
		int reindexed;
		int removed;
		int kept;
		int failed;
	}
}
