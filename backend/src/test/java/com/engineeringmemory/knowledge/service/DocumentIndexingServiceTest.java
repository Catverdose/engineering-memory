package com.engineeringmemory.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.engineeringmemory.aiconfig.config.ModelConcurrencyProperties;
import com.engineeringmemory.embedding.service.EmbeddingService;
import com.engineeringmemory.knowledge.config.KnowledgeProperties;
import com.engineeringmemory.knowledge.enums.DocumentType;
import com.engineeringmemory.knowledge.repository.DocumentRepository;
import com.engineeringmemory.knowledge.repository.DocumentRepository.PendingDocumentReference;
import com.engineeringmemory.knowledge.service.DocumentChunker.ChunkDraft;
import com.engineeringmemory.knowledge.service.DocumentIndexWriter.IndexSource;
import com.engineeringmemory.llm.workload.ModelWorkloadGate;

class DocumentIndexingServiceTest {

	private final DocumentIndexWriter writer = mock(DocumentIndexWriter.class);
	private final DocumentChunker chunker = mock(DocumentChunker.class);
	private final EmbeddingService embeddingService = mock(EmbeddingService.class);
	private final DocumentRepository documentRepository = mock(DocumentRepository.class);
	private DocumentIndexingService service;

	@AfterEach
	void tearDown() {
		if (service != null) {
			service.stop();
		}
	}

	@Test
	@DisplayName("동시 상한을 넘은 세 번째 문서도 큐에서 기다린 뒤 색인된다")
	void queuesBeyondConcurrencyLimitWithoutLosingDocument() throws Exception {
		service = newService(2);
		stubSingleChunk();
		when(writer.prepare(anyLong(), anyLong())).thenAnswer(invocation ->
				source(invocation.getArgument(0), invocation.getArgument(1), 1));

		CountDownLatch twoStarted = new CountDownLatch(2);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch completed = new CountDownLatch(3);
		AtomicInteger active = new AtomicInteger();
		AtomicInteger maxActive = new AtomicInteger();
		when(embeddingService.embedDocuments(anyList())).thenAnswer(invocation -> {
			int now = active.incrementAndGet();
			maxActive.accumulateAndGet(now, Math::max);
			twoStarted.countDown();
			try {
				assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
				return List.<float[]>of(new float[] { 1.0f });
			} finally {
				active.decrementAndGet();
			}
		});
		doAnswer(invocation -> {
			completed.countDown();
			return null;
		}).when(writer).replaceAndMarkReady(any(), anyList(), eq("test-model"));

		service.requestIndex(7L, 101L);
		service.requestIndex(7L, 102L);
		service.requestIndex(7L, 103L);

		assertThat(twoStarted.await(2, TimeUnit.SECONDS)).isTrue();
		assertThat(maxActive).hasValue(2);
		release.countDown();
		assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
		verify(writer, times(3)).prepare(eq(7L), anyLong());
		verify(writer, never()).markFailed(any());
	}

	@Test
	@DisplayName("처리 중 같은 문서 요청 여러 건은 최신 작업 한 번으로 합쳐진다")
	void coalescesSameDocumentAndRerunsLatestGenerationOnce() throws Exception {
		service = newService(2);
		stubSingleChunk();
		AtomicInteger version = new AtomicInteger();
		when(writer.prepare(7L, 101L)).thenAnswer(invocation ->
				source(7L, 101L, version.incrementAndGet()));

		CountDownLatch firstStarted = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		AtomicInteger embeddingCalls = new AtomicInteger();
		when(embeddingService.embedDocuments(anyList())).thenAnswer(invocation -> {
			if (embeddingCalls.incrementAndGet() == 1) {
				firstStarted.countDown();
				assertThat(releaseFirst.await(5, TimeUnit.SECONDS)).isTrue();
			}
			return List.<float[]>of(new float[] { 1.0f });
		});
		CountDownLatch completed = new CountDownLatch(2);
		doAnswer(invocation -> {
			completed.countDown();
			return null;
		}).when(writer).replaceAndMarkReady(any(), anyList(), eq("test-model"));

		service.requestIndex(7L, 101L);
		assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
		for (int i = 0; i < 5; i++) {
			service.requestIndex(7L, 101L);
		}
		releaseFirst.countDown();

		assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
		verify(writer, times(2)).prepare(7L, 101L);
		verify(writer, times(6)).requestIndexing(7L, 101L);
	}

	@Test
	@DisplayName("임베딩 실패는 현재 attempt를 FAILED로 기록한다")
	void marksCurrentAttemptFailed() throws Exception {
		service = newService(2);
		IndexSource source = source(7L, 101L, 1);
		when(writer.prepare(7L, 101L)).thenReturn(source);
		when(chunker.split("content-1")).thenThrow(new IllegalStateException("broken"));
		CountDownLatch failed = new CountDownLatch(1);
		doAnswer(invocation -> {
			failed.countDown();
			return null;
		}).when(writer).markFailed(source);

		service.requestIndex(7L, 101L);

		assertThat(failed.await(5, TimeUnit.SECONDS)).isTrue();
		verify(writer).markFailed(source);
		verify(writer, never()).replaceAndMarkReady(any(), anyList(), any());
	}

	@Test
	@DisplayName("기동 복구는 DB의 PENDING 문서를 새 요청 없이 예약한다")
	void recoversPendingDocumentsOnStartup() throws Exception {
		service = newService(2);
		stubSingleChunk();
		PendingDocumentReference reference = mock(PendingDocumentReference.class);
		when(reference.getOwnerId()).thenReturn(7L);
		when(reference.getId()).thenReturn(101L);
		when(documentRepository.findPendingForIndexing(any())).thenReturn(List.of(reference));
		when(writer.prepare(7L, 101L)).thenReturn(source(7L, 101L, 1));
		CountDownLatch completed = new CountDownLatch(1);
		doAnswer(invocation -> {
			completed.countDown();
			return null;
		}).when(writer).replaceAndMarkReady(any(), anyList(), eq("test-model"));

		service.recoverPendingOnStartup();

		assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
		verify(writer, never()).requestIndexing(anyLong(), anyLong());
		verify(writer).prepare(7L, 101L);
	}

	@Test
	@DisplayName("채팅이 진행 중이면 색인이 임베딩을 부르지 않고 기다린다")
	void indexingStepsAsideWhileSomeoneIsChatting() throws Exception {
		ModelWorkloadGate gate = new ModelWorkloadGate(
				new ModelConcurrencyProperties(1, Duration.ofMinutes(10)));
		assertThat(gate.tryAcquireInteractive()).as("채팅이 모델을 잡았다").isTrue();

		service = newService(1, gate);
		stubSingleChunk();
		when(writer.prepare(anyLong(), anyLong())).thenReturn(source(7L, 101L, 1));

		CountDownLatch embedded = new CountDownLatch(1);
		when(embeddingService.embedDocuments(anyList())).thenAnswer(invocation -> {
			embedded.countDown();
			return List.<float[]>of(new float[] { 1.0f });
		});

		service.requestIndex(7L, 101L);

		assertThat(embedded.await(500, TimeUnit.MILLISECONDS))
				.as("채팅이 진행 중인데 색인이 임베딩을 불렀다")
				.isFalse();

		gate.releaseInteractive();

		assertThat(embedded.await(5, TimeUnit.SECONDS))
				.as("채팅이 끝났는데 색인이 재개되지 않았다")
				.isTrue();
		verify(writer, timeout(5_000)).replaceAndMarkReady(any(), anyList(), eq("test-model"));
	}

	private DocumentIndexingService newService(int maxConcurrent) {
		return newService(maxConcurrent, openGate());
	}

	private DocumentIndexingService newService(int maxConcurrent, ModelWorkloadGate gate) {
		KnowledgeProperties properties = new KnowledgeProperties(
				20 * 1024 * 1024L, 500, 2_000_000, 1200, 150, 2500, 32, maxConcurrent);
		when(embeddingService.embeddingModel()).thenReturn("test-model");
		return new DocumentIndexingService(
				writer, chunker, embeddingService, properties, documentRepository, gate);
	}

	private static ModelWorkloadGate openGate() {
		return new ModelWorkloadGate(new ModelConcurrencyProperties(1, Duration.ofSeconds(30)));
	}

	private void stubSingleChunk() {
		when(chunker.split(any())).thenReturn(List.of(new ChunkDraft(0, null, "chunk")));
		when(embeddingService.embedDocuments(anyList()))
				.thenReturn(List.<float[]>of(new float[] { 1.0f }));
	}

	private static IndexSource source(long ownerId, long documentId, int version) {
		return new IndexSource(
				documentId,
				ownerId,
				version,
				"attempt-" + version,
				"title",
				DocumentType.NOTE,
				List.of("project"),
				List.of("Java"),
				List.of("tag"),
				"content-" + version);
	}
}
