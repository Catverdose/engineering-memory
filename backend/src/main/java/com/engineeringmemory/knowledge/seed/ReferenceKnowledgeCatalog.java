package com.engineeringmemory.knowledge.seed;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.engineeringmemory.knowledge.dto.request.DocumentCreateRequest;
import com.engineeringmemory.knowledge.enums.DocumentType;

import tools.jackson.databind.ObjectMapper;

@Component
public class ReferenceKnowledgeCatalog {

	static final String LOCATION = "knowledge/reference-knowledge.json";
	static final int SCHEMA_VERSION = 2;

	private final ObjectMapper objectMapper;

	public ReferenceKnowledgeCatalog(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public List<DocumentCreateRequest> load() {
		try (InputStream in = new ClassPathResource(LOCATION).getInputStream()) {
			return toRequests(objectMapper.readValue(in, Manifest.class));
		} catch (IOException e) {
			throw new UncheckedIOException("기본 지식 카탈로그를 읽을 수 없습니다: " + LOCATION, e);
		}
	}

	static List<DocumentCreateRequest> toRequests(Manifest manifest) {
		if (manifest == null || manifest.schemaVersion() != SCHEMA_VERSION || manifest.documents() == null) {
			throw new IllegalStateException("기본 지식 카탈로그 양식(schemaVersion 2)이 올바르지 않습니다.");
		}
		Set<String> titles = new HashSet<>();
		Set<String> contents = new HashSet<>();
		List<DocumentCreateRequest> requests = new ArrayList<>(manifest.documents().size());
		for (Entry entry : manifest.documents()) {
			String title = requireText(entry.title(), "title", entry);
			if (!titles.add(title)) {
				throw new IllegalStateException("기본 지식 제목이 중복됩니다: " + title);
			}
			if (entry.documentType() == null) {
				throw new IllegalStateException("documentType 이 없습니다: " + title);
			}
			if (entry.projects() != null && !entry.projects().isEmpty()) {
				throw new IllegalStateException("공용 자료에는 프로젝트를 넣지 않습니다: " + title);
			}
			String sourceUri = requireText(entry.sourceUri(), "sourceUri", entry);
			if (!"https".equals(URI.create(sourceUri).getScheme())) {
				throw new IllegalStateException("공용 자료 출처는 HTTPS URL이어야 합니다: " + title);
			}
			String content = requireText(entry.content(), "content", entry).strip()
					+ "\n\n출처: " + sourceUri;
			if (!contents.add(content)) {
				throw new IllegalStateException("기본 지식 본문이 중복됩니다: " + title);
			}
			requests.add(new DocumentCreateRequest(
					title, entry.documentType(), List.of(),
					entry.technologies() == null ? List.of() : List.copyOf(entry.technologies()),
					entry.tags() == null ? List.of() : List.copyOf(entry.tags()),
					entry.occurredOn(), sourceUri, content));
		}
		return List.copyOf(requests);
	}

	private static String requireText(String value, String field, Entry entry) {
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(field + " 가 비어 있습니다: " + entry.title());
		}
		return value.strip();
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Manifest(int schemaVersion, List<Entry> documents) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Entry(
			String title,
			DocumentType documentType,
			List<String> projects,
			List<String> technologies,
			List<String> tags,
			LocalDate occurredOn,
			String sourceUri,
			String content) {
	}
}
