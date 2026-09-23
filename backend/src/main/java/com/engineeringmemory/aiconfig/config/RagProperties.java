package com.engineeringmemory.aiconfig.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

@Validated
@ConfigurationProperties(prefix = "rag")
public record RagProperties(

		@Positive @Max(50) @DefaultValue("5") int topK,

		@DecimalMin("0.0") @DecimalMax("1.0") @DefaultValue("0.55") double similarityThreshold,

		@Min(2048) @Max(100000) @DefaultValue("10000") int maxPromptChars,

		@Positive @Max(10) @DefaultValue("3") int maxChunksPerDocument) {
}
