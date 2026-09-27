package com.engineeringmemory.knowledge.seed;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.InvalidDataAccessResourceUsageException;

import com.engineeringmemory.embedding.service.EmbeddingService;
import com.engineeringmemory.knowledge.config.KnowledgeSeedProperties;
import com.engineeringmemory.knowledge.dto.request.DocumentCreateRequest;
import com.engineeringmemory.knowledge.entity.Document;
import com.engineeringmemory.knowledge.enums.DocumentType;
import com.engineeringmemory.knowledge.repository.DocumentRepository;
import com.engineeringmemory.knowledge.service.DocumentService;
import com.engineeringmemory.llm.service.LlmService;

class ReferenceKnowledgeSeederTest {

	@Test
	@DisplayName("꺼져 있으면 카탈로그도 DB도 보지 않는다")
	void doesNothingWhenDisabled() {
		Fixture fixture = new Fixture(false);

		fixture.seeder.trySync();

		verifyNoInteractions(fixture.catalog, fixture.documents, fixture.service, fixture.llm);
	}

	@Test
	@DisplayName("임베딩 모델이 준비되지 않으면 기다렸다가 준비되면 한 번만 동기화한다")
	void waitsForEmbeddingModelThenSyncsOnce() {
		Fixture fixture = new Fixture(true);
		when(fixture.llm.isEmbeddingAvailable()).thenReturn(false, true);
		when(fixture.catalog.load()).thenReturn(List.of(request("새 자료", "본문")));

		fixture.seeder.trySync();
		verifyNoInteractions(fixture.service);

		fixture.seeder.trySync();
		fixture.seeder.trySync();

		verify(fixture.catalog, times(1)).load();
		verify(fixture.service, times(1)).createShared(any());
	}

	@Test
	@DisplayName("없는 자료는 만들고, 바뀐 자료는 갱신하고, 같은 자료는 둔다")
	void createsUpdatesAndKeeps() {
		Fixture fixture = new Fixture(true);
		Document same = shared(1L, "같은 자료", Document.IndexingStatus.READY, "bge-m3");
		Document changed = shared(2L, "바뀐 자료", Document.IndexingStatus.READY, "bge-m3");
		fixture.existing(same, changed);
		DocumentCreateRequest sameRequest = request("같은 자료", "본문");
		DocumentCreateRequest changedRequest = request("바뀐 자료", "새 본문");
		DocumentCreateRequest newRequest = request("새 자료", "본문");
		when(fixture.service.sharedMatches(same, sameRequest)).thenReturn(true);
		when(fixture.service.sharedMatches(changed, changedRequest)).thenReturn(false);

		fixture.seeder.sync(List.of(sameRequest, changedRequest, newRequest));

		verify(fixture.service).createShared(newRequest);
		verify(fixture.service).replaceShared(2L, changedRequest);
		verify(fixture.service, never()).replaceShared(eq(1L), any());
		verify(fixture.service, never()).reindexShared(anyLong());
		verify(fixture.service, never()).deleteShared(anyLong());
	}

	@Test
	@DisplayName("색인 실패했거나 임베딩 모델이 바뀐 공용 자료는 다시 색인한다")
	void reindexesFailedOrStaleDocuments() {
		Fixture fixture = new Fixture(true);
		Document failed = shared(1L, "실패", Document.IndexingStatus.FAILED, null);
		Document stale = shared(2L, "옛 모델", Document.IndexingStatus.READY, "old-model");
		Document pending = shared(3L, "대기", Document.IndexingStatus.PENDING, null);
		fixture.existing(failed, stale, pending);
		when(fixture.service.sharedMatches(any(), any())).thenReturn(true);

		fixture.seeder.sync(List.of(request("실패", "본문"), request("옛 모델", "본문"), request("대기", "본문")));

		verify(fixture.service).reindexShared(1L);
		verify(fixture.service).reindexShared(2L);
		verify(fixture.service, never()).reindexShared(3L);
	}

	@Test
	@DisplayName("카탈로그에서 빠진 공용 자료는 지운다")
	void deletesSharedDocumentsMissingFromCatalog() {
		Fixture fixture = new Fixture(true);
		fixture.existing(shared(9L, "사라진 자료", Document.IndexingStatus.READY, "bge-m3"));

		fixture.seeder.sync(List.of());

		verify(fixture.service).deleteShared(9L);
	}

	@Test
	@DisplayName("한 건이 실패해도 나머지는 계속 반영한다")
	void continuesAfterSingleEntryFailure() {
		Fixture fixture = new Fixture(true);
		DocumentCreateRequest broken = request("깨진 자료", "본문");
		DocumentCreateRequest fine = request("정상 자료", "본문");
		when(fixture.service.createShared(broken)).thenThrow(new IllegalArgumentException("bad"));

		fixture.seeder.sync(List.of(broken, fine));

		verify(fixture.service).createShared(fine);
	}

	@Test
	@DisplayName("DB가 공용 문서를 받지 못하면 기동을 막지 않고 멈춘다")
	void stopsWithoutThrowingWhenSchemaIsOld() {
		Fixture fixture = new Fixture(true);
		when(fixture.llm.isEmbeddingAvailable()).thenReturn(true);
		when(fixture.catalog.load()).thenReturn(List.of(request("자료", "본문"), request("다른 자료", "본문")));
		doThrow(new InvalidDataAccessResourceUsageException("owner_id not null"))
				.when(fixture.service).createShared(any());

		fixture.seeder.trySync();
		fixture.seeder.trySync();

		verify(fixture.service, times(1)).createShared(any());
	}

	private static DocumentCreateRequest request(String title, String content) {
		return new DocumentCreateRequest(title, DocumentType.TECH_DOC, List.of(), List.of("HTTP"),
				List.of("일반정보"), null, "https://example.com", content);
	}

	private static Document shared(long id, String title, Document.IndexingStatus status, String model) {
		Document document = Document.create(null, title, DocumentType.TECH_DOC, List.of(), List.of(), List.of(),
				"doc.txt", "https://example.com", "text/plain;charset=UTF-8", null, "본문",
				"본문".getBytes(StandardCharsets.UTF_8), "hash-" + id);
		set(document, "id", id);
		set(document, "indexingStatus", status);
		set(document, "embeddingModel", model);
		return document;
	}

	private static void set(Document document, String field, Object value) {
		try {
			Field target = Document.class.getDeclaredField(field);
			target.setAccessible(true);
			target.set(document, value);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static final class Fixture {
		final ReferenceKnowledgeCatalog catalog = mock(ReferenceKnowledgeCatalog.class);
		final DocumentRepository documents = mock(DocumentRepository.class);
		final DocumentService service = mock(DocumentService.class);
		final EmbeddingService embedding = mock(EmbeddingService.class);
		final LlmService llm = mock(LlmService.class);
		final ReferenceKnowledgeSeeder seeder;

		Fixture(boolean enabled) {
			when(embedding.embeddingModel()).thenReturn("bge-m3");
			when(documents.findByOwnerIdIsNull()).thenReturn(List.of());
			seeder = new ReferenceKnowledgeSeeder(
					new KnowledgeSeedProperties(enabled, Duration.ofSeconds(30)),
					catalog, documents, service, embedding, llm);
		}

		void existing(Document... shared) {
			when(documents.findByOwnerIdIsNull()).thenReturn(List.of(shared));
		}
	}
}
