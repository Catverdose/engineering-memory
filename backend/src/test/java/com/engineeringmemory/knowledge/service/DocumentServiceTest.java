package com.engineeringmemory.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

import com.engineeringmemory.common.exception.BusinessException;
import com.engineeringmemory.common.exception.ErrorCode;
import com.engineeringmemory.embedding.service.EmbeddingService;
import com.engineeringmemory.knowledge.dto.request.DocumentCreateRequest;
import com.engineeringmemory.knowledge.dto.request.DocumentMetadataRequest;
import com.engineeringmemory.knowledge.entity.Document;
import com.engineeringmemory.knowledge.enums.DocumentType;
import com.engineeringmemory.knowledge.repository.DocumentListRepository;
import com.engineeringmemory.knowledge.repository.DocumentRepository;

class DocumentServiceTest {

	private static final long OWNER = 42L;

	@Test
	@DisplayName("라벨은 공백을 정리하고 대소문자가 같으면 하나로 본다")
	void normalizesLabels() {
		Fixture fixture = new Fixture();
		fixture.savesWithId(11L);

		fixture.service.createText(OWNER, request(
				"제목", List.of("Spring", " spring ", "SPRING"), List.of("Redis", "redis"), List.of()));

		Document saved = fixture.savedDocument();
		assertThat(saved.getProjects()).containsExactly("Spring");
		assertThat(saved.getTechnologies()).containsExactly("Redis");
	}

	@Test
	@DisplayName("빈 라벨은 버린다")
	void dropsBlankLabels() {
		Fixture fixture = new Fixture();
		fixture.savesWithId(11L);

		fixture.service.createText(OWNER, request(
				"제목", Arrays.asList("ubot", "   ", ""), List.of(), List.of()));

		assertThat(fixture.savedDocument().getProjects()).containsExactly("ubot");
	}

	@Test
	@DisplayName("제목으로 만든 파일명에 경로 구분자를 남기지 않는다")
	void textFilenameHasNoPathSeparators() {
		Fixture fixture = new Fixture();
		fixture.savesWithId(11L);

		fixture.service.createText(OWNER, request(
				"../../etc/passwd\\메모", List.of(), List.of(), List.of()));

		assertThat(fixture.savedDocument().getSourceName())
				.doesNotContain("/", "\\")
				.endsWith(".txt");
	}

	@Test
	@DisplayName("긴 제목으로 만든 파일명이 컬럼 길이를 넘지 않는다")
	void textFilenameFitsInColumn() {
		Fixture fixture = new Fixture();
		fixture.savesWithId(11L);

		fixture.service.createText(OWNER, request("가".repeat(500), List.of(), List.of(), List.of()));

		assertThat(fixture.savedDocument().getSourceName())
				.hasSizeLessThanOrEqualTo(300)
				.endsWith(".txt");
	}

	@Test
	@DisplayName("중복 판정은 소유자 안에서만 한다")
	void duplicateCheckIsScopedToOwner() {
		Fixture fixture = new Fixture();
		fixture.savesWithId(11L);

		fixture.service.createText(OWNER, request("제목", List.of(), List.of(), List.of()));

		verify(fixture.documents).existsByOwnerIdAndContentHash(eq(OWNER), anyString());
	}

	@Test
	@DisplayName("같은 내용을 다시 올리면 거절한다")
	void rejectsDuplicateContent() {
		Fixture fixture = new Fixture();
		when(fixture.documents.existsByOwnerIdAndContentHash(eq(OWNER), anyString())).thenReturn(true);

		assertThatThrownBy(() -> fixture.service.createText(
				OWNER, request("제목", List.of(), List.of(), List.of())))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_DOCUMENT));

		verify(fixture.documents, never()).saveAndFlush(any());
		verify(fixture.indexing, never()).requestIndex(anyLong(), anyLong());
	}

	@Test
	@DisplayName("동시에 같은 내용이 들어오면 제약 위반을 중복으로 바꿔 알린다")
	void translatesConstraintRaceIntoDuplicate() {
		Fixture fixture = new Fixture();
		when(fixture.documents.existsByOwnerIdAndContentHash(eq(OWNER), anyString()))
				.thenReturn(false)
				.thenReturn(true);
		when(fixture.documents.saveAndFlush(any()))
				.thenThrow(new DataIntegrityViolationException("uq_document_owner_content_hash"));

		assertThatThrownBy(() -> fixture.service.createText(
				OWNER, request("제목", List.of(), List.of(), List.of())))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_DOCUMENT));
	}

	@Test
	@DisplayName("중복이 아닌 제약 위반은 감추지 않는다")
	void doesNotMaskUnrelatedConstraintViolations() {
		Fixture fixture = new Fixture();
		when(fixture.documents.existsByOwnerIdAndContentHash(eq(OWNER), anyString())).thenReturn(false);
		when(fixture.documents.saveAndFlush(any()))
				.thenThrow(new DataIntegrityViolationException("fk_document_owner"));

		assertThatThrownBy(() -> fixture.service.createText(
				OWNER, request("제목", List.of(), List.of(), List.of())))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("저장을 마친 뒤에 색인을 예약한다")
	void schedulesIndexingOnlyAfterSave() {
		Fixture fixture = new Fixture();
		fixture.savesWithId(11L);

		fixture.service.createText(OWNER, request("제목", List.of(), List.of(), List.of()));

		InOrder order = inOrder(fixture.documents, fixture.indexing);
		order.verify(fixture.documents).saveAndFlush(any());
		order.verify(fixture.indexing).requestIndex(OWNER, 11L);

		verify(fixture.indexing, times(1)).requestIndex(anyLong(), anyLong());
	}

	@Test
	@DisplayName("남의 문서는 없는 것처럼 답한다")
	void othersDocumentLooksMissing() {
		Fixture fixture = new Fixture();
		when(fixture.documents.findByIdAndOwnerId(999L, OWNER)).thenReturn(Optional.empty());

		for (Runnable call : List.<Runnable>of(
				() -> fixture.service.detail(OWNER, 999L),
				() -> fixture.service.delete(OWNER, 999L),
				() -> fixture.service.original(OWNER, 999L),
				() -> fixture.service.updateMetadata(OWNER, 999L, metadata("제목")))) {
			assertThatThrownBy(call::run)
					.isInstanceOfSatisfying(BusinessException.class,
							e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
		}
	}

	@Test
	@DisplayName("조회는 소유자를 조건에 넣는다")
	void lookupCarriesOwner() {
		Fixture fixture = new Fixture();
		fixture.owns(11L);

		fixture.service.detail(OWNER, 11L);

		verify(fixture.documents).findByIdAndOwnerId(11L, OWNER);
	}

	@Test
	@DisplayName("PDF 본문은 텍스트 수정으로 바꿀 수 없다")
	void refusesTextUpdateOnPdf() {
		Fixture fixture = new Fixture();
		fixture.owns(11L, "application/pdf");
		when(fixture.documents.existsByOwnerIdAndContentHashAndIdNot(eq(OWNER), anyString(), eq(11L)))
				.thenReturn(false);

		assertThatThrownBy(() -> fixture.service.updateText(
				OWNER, 11L, request("제목", List.of(), List.of(), List.of())))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE));
	}

	@Test
	@DisplayName("다른 요청이 먼저 고쳤으면 덮어쓰지 않고 알린다")
	void reportsConcurrentModification() {
		Fixture fixture = new Fixture();
		fixture.owns(11L);
		when(fixture.documents.saveAndFlush(any()))
				.thenThrow(new OptimisticLockingFailureException("version"));

		assertThatThrownBy(() -> fixture.service.updateMetadata(OWNER, 11L, metadata("새 제목")))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);
					assertThat(e.getErrorCode().isRetryable()).isTrue();
				});
	}

	@Test
	@DisplayName("메타데이터만 바뀌어도 다시 색인한다")
	void reindexesAfterMetadataChange() {
		Fixture fixture = new Fixture();
		fixture.owns(11L);

		fixture.service.updateMetadata(OWNER, 11L, metadata("새 제목"));

		verify(fixture.indexing).requestIndex(OWNER, 11L);
	}

	private static DocumentCreateRequest request(
			String title, List<String> projects, List<String> technologies, List<String> tags) {
		return new DocumentCreateRequest(title, DocumentType.TROUBLESHOOTING,
				projects, technologies, tags, LocalDate.of(2026, 1, 1), null, "본문");
	}

	private static DocumentMetadataRequest metadata(String title) {
		return new DocumentMetadataRequest(title, DocumentType.NOTE,
				List.of(), List.of(), List.of(), LocalDate.of(2026, 1, 1), null);
	}

	private static final class Fixture {
		final DocumentRepository documents = mock(DocumentRepository.class);
		final DocumentListRepository list = mock(DocumentListRepository.class);
		final KnowledgeFileExtractor extractor = mock(KnowledgeFileExtractor.class);
		final DocumentIndexingService indexing = mock(DocumentIndexingService.class);
		final EmbeddingService embedding = mock(EmbeddingService.class);
		final DocumentService service;

		Fixture() {
			when(extractor.validateText(anyString())).thenAnswer(i -> i.getArgument(0));
			when(embedding.embeddingModel()).thenReturn("bge-m3");
			this.service = new DocumentService(documents, list, extractor, indexing, embedding);
		}

		void savesWithId(long id) {
			when(documents.existsByOwnerIdAndContentHash(anyLong(), anyString())).thenReturn(false);
			when(documents.saveAndFlush(any())).thenAnswer(invocation -> {
				Document document = invocation.getArgument(0);
				withId(document, id);
				when(documents.findByIdAndOwnerId(id, OWNER)).thenReturn(Optional.of(document));
				return document;
			});
		}

		Document owns(long id) {
			return owns(id, "text/plain;charset=UTF-8");
		}

		Document owns(long id, String mediaType) {
			Document document = withId(Document.create(OWNER, "제목", DocumentType.NOTE,
					List.of(), List.of(), List.of(), "note.md", null, mediaType,
					LocalDate.of(2026, 1, 1), "본문", "본문".getBytes(StandardCharsets.UTF_8), "hash"), id);
			when(documents.findByIdAndOwnerId(id, OWNER)).thenReturn(Optional.of(document));
			when(documents.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
			return document;
		}

		Document savedDocument() {
			ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
			verify(documents).saveAndFlush(captor.capture());
			return captor.getValue();
		}

		private static Document withId(Document document, long id) {
			try {
				Field field = Document.class.getDeclaredField("id");
				field.setAccessible(true);
				field.set(document, id);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
			return document;
		}
	}
}
