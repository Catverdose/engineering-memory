package com.engineeringmemory.knowledge.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.engineeringmemory.knowledge.dto.request.DocumentCreateRequest;
import com.engineeringmemory.knowledge.enums.DocumentType;
import com.engineeringmemory.knowledge.seed.ReferenceKnowledgeCatalog.Entry;
import com.engineeringmemory.knowledge.seed.ReferenceKnowledgeCatalog.Manifest;

import tools.jackson.databind.ObjectMapper;

class ReferenceKnowledgeCatalogTest {

	private final ReferenceKnowledgeCatalog catalog = new ReferenceKnowledgeCatalog(new ObjectMapper());

	@Test
	@DisplayName("동봉한 카탈로그는 등록 요청 제약을 모두 지킨다")
	void bundledCatalogSatisfiesRequestConstraints() {
		List<DocumentCreateRequest> entries = catalog.load();

		assertThat(entries).hasSizeGreaterThan(300);
		Set<String> titles = new HashSet<>();
		for (DocumentCreateRequest entry : entries) {
			assertThat(titles.add(entry.title())).as("제목 중복: %s", entry.title()).isTrue();
			assertThat(entry.title()).hasSizeLessThanOrEqualTo(300);
			assertThat(entry.documentType()).isNotNull();
			assertThat(entry.projects()).as("공용 자료에 프로젝트: %s", entry.title()).isEmpty();
			assertThat(entry.technologies()).hasSizeLessThanOrEqualTo(30);
			assertThat(entry.tags()).hasSizeLessThanOrEqualTo(50).contains("일반정보");
			assertThat(entry.sourceUri()).startsWith("https://").hasSizeLessThanOrEqualTo(2000);
			assertThat(entry.content()).endsWith("\n\n출처: " + entry.sourceUri());
		}
	}

	@Test
	@DisplayName("개인 경험 태그가 붙은 자료는 공용 카탈로그에 넣지 않는다")
	void bundledCatalogHasNoPersonalExperience() {
		assertThat(catalog.load())
				.noneMatch(entry -> entry.tags().contains("프로젝트경험"))
				.noneMatch(entry -> entry.content().contains("UBot"));
	}

	@Test
	@DisplayName("공용 자료에 프로젝트가 있으면 거부한다")
	void rejectsProjectOnSharedEntry() {
		Manifest manifest = new Manifest(2, List.of(entry("제목", List.of("ubot"), "https://example.com", "본문")));

		assertThatThrownBy(() -> ReferenceKnowledgeCatalog.toRequests(manifest))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("프로젝트");
	}

	@Test
	@DisplayName("출처가 HTTPS가 아니면 거부한다")
	void rejectsNonHttpsSource() {
		Manifest manifest = new Manifest(2, List.of(entry("제목", List.of(), "http://example.com", "본문")));

		assertThatThrownBy(() -> ReferenceKnowledgeCatalog.toRequests(manifest))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("HTTPS");
	}

	@Test
	@DisplayName("제목이 겹치면 거부한다. 동기화는 제목으로 공용 문서를 식별한다")
	void rejectsDuplicateTitle() {
		Manifest manifest = new Manifest(2, List.of(
				entry("같은 제목", List.of(), "https://a.example", "본문 A"),
				entry("같은 제목", List.of(), "https://b.example", "본문 B")));

		assertThatThrownBy(() -> ReferenceKnowledgeCatalog.toRequests(manifest))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("중복");
	}

	@Test
	@DisplayName("본문 끝에 출처를 붙인다. seed 등록 스크립트와 같은 형식이다")
	void appendsSourceToContent() {
		Manifest manifest = new Manifest(2, List.of(entry("제목", List.of(), "https://a.example", "  본문  ")));

		assertThat(ReferenceKnowledgeCatalog.toRequests(manifest).get(0).content())
				.isEqualTo("본문\n\n출처: https://a.example");
	}

	@Test
	@DisplayName("양식 버전이 다르면 거부한다")
	void rejectsUnknownSchemaVersion() {
		assertThatThrownBy(() -> ReferenceKnowledgeCatalog.toRequests(new Manifest(1, List.of())))
				.isInstanceOf(IllegalStateException.class);
	}

	private static Entry entry(String title, List<String> projects, String sourceUri, String content) {
		return new Entry(title, DocumentType.TECH_DOC, projects, List.of("HTTP"), List.of("일반정보"),
				null, sourceUri, content);
	}
}
