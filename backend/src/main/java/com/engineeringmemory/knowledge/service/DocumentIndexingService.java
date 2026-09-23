package com.engineeringmemory.knowledge.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.engineeringmemory.common.exception.BusinessException;
import com.engineeringmemory.common.exception.ErrorCode;
import com.engineeringmemory.embedding.service.EmbeddingService;
import com.engineeringmemory.knowledge.config.KnowledgeProperties;
import com.engineeringmemory.knowledge.repository.DocumentRepository;
import com.engineeringmemory.knowledge.repository.DocumentRepository.PendingDocumentReference;
import com.engineeringmemory.knowledge.service.DocumentChunker.ChunkDraft;
import com.engineeringmemory.knowledge.service.DocumentIndexWriter.IndexSource;
import com.engineeringmemory.knowledge.service.DocumentIndexWriter.IndexedChunk;
import com.engineeringmemory.llm.workload.ModelWorkloadGate;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class DocumentIndexingService {

	private static final int MIN_QUEUE_CAPACITY = 32;
	private static final int MIN_RECOVERY_BATCH_SIZE = 100;

	private final DocumentIndexWriter writer;
	private final DocumentChunker chunker;
	private final EmbeddingService embeddingService;
	private final KnowledgeProperties properties;
	private final DocumentRepository documentRepository;
	private final ModelWorkloadGate modelGate;
	private final ThreadPoolExecutor executor;
	private final int recoveryBatchSize;
	private final ConcurrentMap<DocumentKey, WorkState> workByDocument = new ConcurrentHashMap<>();
	private final AtomicBoolean stopping = new AtomicBoolean();

	@Autowired
	public DocumentIndexingService(
			DocumentIndexWriter writer,
			DocumentChunker chunker,
			EmbeddingService embeddingService,
			KnowledgeProperties properties,
			DocumentRepository documentRepository,
			ModelWorkloadGate modelGate) {
		this(writer, chunker, embeddingService, properties, documentRepository, modelGate,
				newExecutor(properties.maxConcurrentIndexing()));
	}

	DocumentIndexingService(
			DocumentIndexWriter writer,
			DocumentChunker chunker,
			EmbeddingService embeddingService,
			KnowledgeProperties properties,
			DocumentRepository documentRepository,
			ModelWorkloadGate modelGate,
			ThreadPoolExecutor executor) {
		this.writer = writer;
		this.chunker = chunker;
		this.embeddingService = embeddingService;
		this.properties = properties;
		this.documentRepository = documentRepository;
		this.modelGate = modelGate;
		this.executor = executor;
		this.recoveryBatchSize = Math.max(MIN_RECOVERY_BATCH_SIZE,
				executor.getMaximumPoolSize() + executor.getQueue().remainingCapacity());
	}

	public void requestIndex(long ownerId, long documentId) {
		writer.requestIndexing(ownerId, documentId);
		enqueue(new DocumentKey(ownerId, documentId), true);
	}

	@EventListener(ApplicationReadyEvent.class)
	public void recoverPendingOnStartup() {
		recoverPending();
	}

	@Scheduled(fixedDelayString = "${knowledge.indexing.recovery-poll-delay:10s}")
	void recoverPending() {
		if (stopping.get()) {
			return;
		}
		try {
			List<PendingDocumentReference> pending = documentRepository.findPendingForIndexing(
					PageRequest.of(0, recoveryBatchSize));
			for (PendingDocumentReference reference : pending) {
				enqueue(new DocumentKey(reference.getOwnerId(), reference.getId()), false);
			}
			if (!pending.isEmpty()) {
				log.info("미완료 문서 색인 복구 예약: candidateCount={}, trackedCount={}",
						pending.size(), workByDocument.size());
			}
		} catch (RuntimeException e) {
			log.warn("미완료 문서 색인 복구 조회 실패: cause={}", e.getClass().getSimpleName());
		}
	}

	private void enqueue(DocumentKey key, boolean newerRequest) {
		while (!stopping.get()) {
			WorkState fresh = new WorkState();
			WorkState state = workByDocument.putIfAbsent(key, fresh);
			if (state == null) {
				state = fresh;
				try {
					WorkState scheduledState = state;
					executor.execute(() -> runCoalesced(key, scheduledState));
				} catch (RejectedExecutionException e) {
					synchronized (state) {
						state.closed = true;
						workByDocument.remove(key, state);
					}
					log.info("문서 색인 큐가 가득 차 PENDING으로 유지: documentId={}, ownerId={}",
							key.documentId(), key.ownerId());
				}
				return;
			}

			synchronized (state) {
				if (state.closed || workByDocument.get(key) != state) {
					continue;
				}
				if (newerRequest) {
					state.requestedGeneration++;
				}
				return;
			}
		}
	}

	private void runCoalesced(DocumentKey key, WorkState state) {
		while (!stopping.get()) {
			long generation;
			synchronized (state) {
				if (state.closed) {
					return;
				}
				generation = state.requestedGeneration;
			}

			runOnce(key);

			synchronized (state) {
				if (generation != state.requestedGeneration) {
					continue;
				}
				state.closed = true;
				workByDocument.remove(key, state);
				return;
			}
		}
	}

	private void runOnce(DocumentKey key) {
		IndexSource source;
		try {
			source = writer.prepare(key.ownerId(), key.documentId());
		} catch (BusinessException e) {
			if (e.getErrorCode() != ErrorCode.NOT_FOUND) {
				log.warn("문서 색인 준비 실패: documentId={}, ownerId={}, cause={}",
						key.documentId(), key.ownerId(), e.getErrorCode());
			}
			return;
		} catch (RuntimeException e) {
			log.warn("문서 색인 준비 실패: documentId={}, ownerId={}, cause={}",
					key.documentId(), key.ownerId(), e.getClass().getSimpleName());
			return;
		}

		indexPrepared(source);
	}

	private void indexPrepared(IndexSource source) {
		try {
			List<ChunkDraft> drafts = chunker.split(source.content());
			List<String> embeddingInputs = drafts.stream()
					.map(draft -> embeddingText(source, draft))
					.toList();
			List<float[]> embeddings = embedInBatches(embeddingInputs);

			List<IndexedChunk> indexed = new ArrayList<>(drafts.size());
			for (int i = 0; i < drafts.size(); i++) {
				ChunkDraft draft = drafts.get(i);
				indexed.add(new IndexedChunk(draft.index(), draft.heading(), draft.content(), embeddings.get(i)));
			}
			writer.replaceAndMarkReady(source, indexed, embeddingService.embeddingModel());
			log.info("문서 색인 완료: documentId={}, ownerId={}, version={}, chunkCount={}",
					source.documentId(), source.ownerId(), source.documentVersion(), indexed.size());
		} catch (RuntimeException e) {
			if (!stopping.get() && !Thread.currentThread().isInterrupted()) {
				try {
					writer.markFailed(source);
				} catch (RuntimeException markFailure) {
					log.warn("문서 색인 실패 상태 기록 실패: documentId={}, ownerId={}, cause={}",
							source.documentId(), source.ownerId(), markFailure.getClass().getSimpleName());
				}
			}
			log.warn("문서 색인 실패: documentId={}, ownerId={}, version={}, cause={}",
					source.documentId(), source.ownerId(),
					source.documentVersion(), e.getClass().getSimpleName());
		}
	}

	private List<float[]> embedInBatches(List<String> inputs) {
		List<float[]> all = new ArrayList<>(inputs.size());
		int batchSize = properties.embeddingBatchSize();
		for (int from = 0; from < inputs.size(); from += batchSize) {
			modelGate.yieldToInteractive();
			int to = Math.min(from + batchSize, inputs.size());
			all.addAll(embeddingService.embedDocuments(inputs.subList(from, to)));
		}
		if (all.size() != inputs.size()) {
			throw new IllegalStateException("임베딩 결과 개수가 청크 개수와 다릅니다.");
		}
		return all;
	}

	@PreDestroy
	void stop() {
		stopping.set(true);
		executor.shutdownNow();
	}

	private static ThreadPoolExecutor newExecutor(int maxConcurrent) {
		int queueCapacity = Math.max(MIN_QUEUE_CAPACITY, maxConcurrent * 16);
		return new ThreadPoolExecutor(
				maxConcurrent,
				maxConcurrent,
				0L,
				TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(queueCapacity),
				daemonThreadFactory(),
				new ThreadPoolExecutor.AbortPolicy());
	}

	private static ThreadFactory daemonThreadFactory() {
		AtomicInteger sequence = new AtomicInteger();
		return task -> {
			Thread thread = new Thread(task, "document-indexing-" + sequence.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
	}

	private static String embeddingText(IndexSource source, ChunkDraft draft) {
		StringBuilder text = new StringBuilder(draft.content().length() + 300)
				.append("문서: ").append(source.title()).append('\n')
				.append("자료 유형: ").append(source.documentType().getLabel()).append('\n');
		if (!source.projects().isEmpty()) {
			text.append("프로젝트: ").append(String.join(", ", source.projects())).append('\n');
		}
		if (!source.technologies().isEmpty()) {
			text.append("기술: ").append(String.join(", ", source.technologies())).append('\n');
		}
		if (!source.tags().isEmpty()) {
			text.append("태그: ").append(String.join(", ", source.tags())).append('\n');
		}
		if (draft.heading() != null) {
			text.append("섹션: ").append(draft.heading()).append('\n');
		}
		return text.append("본문:\n").append(draft.content()).toString();
	}

	private record DocumentKey(long ownerId, long documentId) {
	}

	private static final class WorkState {
		private long requestedGeneration = 1;
		private boolean closed;
	}
}
