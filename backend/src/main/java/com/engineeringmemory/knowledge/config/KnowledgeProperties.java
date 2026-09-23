package com.engineeringmemory.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

@Validated
@ConfigurationProperties(prefix = "knowledge.indexing")
public record KnowledgeProperties(

		@Positive @Max(20 * 1024 * 1024) @DefaultValue("20971520") long maxFileBytes,

		@Positive @Max(2000) @DefaultValue("500") int maxPdfPages,

		@Positive @Max(5_000_000) @DefaultValue("2000000") int maxExtractedChars,

		@Min(200) @Max(8000) @DefaultValue("1200") int chunkSizeChars,

		@Min(0) @Max(2000) @DefaultValue("150") int chunkOverlapChars,

		@Positive @Max(5000) @DefaultValue("2500") int maxChunks,

		@Positive @Max(256) @DefaultValue("32") int embeddingBatchSize,

		@Positive @Max(10) @DefaultValue("2") int maxConcurrentIndexing) {

	public KnowledgeProperties {
		if (chunkOverlapChars >= chunkSizeChars) {
			throw new IllegalArgumentException(
					"knowledge.indexing.chunk-overlap-chars 는 chunk-size-chars 보다 작아야 합니다.");
		}
	}
}
