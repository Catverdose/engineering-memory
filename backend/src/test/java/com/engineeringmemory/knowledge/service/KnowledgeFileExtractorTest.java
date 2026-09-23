package com.engineeringmemory.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.engineeringmemory.knowledge.config.KnowledgeProperties;

class KnowledgeFileExtractorTest {

	private final KnowledgeFileExtractor extractor = new KnowledgeFileExtractor(
			new KnowledgeProperties(
					20 * 1024 * 1024L,
					500,
					2_000_000,
					1200,
					150,
					2500,
					32,
					2));

	@Test
	void acceptsExtensionlessReadmeAsUtf8Text() {
		MockMultipartFile file = new MockMultipartFile(
				"file", "README", "application/octet-stream",
				"# 프로젝트\n설계 기록".getBytes(StandardCharsets.UTF_8));

		ExtractedDocument extracted = extractor.extract(file);

		assertThat(extracted.sourceName()).isEqualTo("README");
		assertThat(extracted.mediaType()).isEqualTo("text/plain;charset=UTF-8");
		assertThat(extracted.content()).isEqualTo("# 프로젝트\n설계 기록");
	}
}
