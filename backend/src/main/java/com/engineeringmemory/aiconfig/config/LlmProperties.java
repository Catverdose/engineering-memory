package com.engineeringmemory.aiconfig.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import com.engineeringmemory.aiconfig.enums.LlmProvider;

import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "llm")
public record LlmProperties(

		@NotNull @DefaultValue("OLLAMA") LlmProvider provider,

		LlmProvider embeddingProvider,

		LlmProvider generationProvider) {

	public LlmProvider embeddingProviderOrDefault() {
		return embeddingProvider == null ? provider : embeddingProvider;
	}

	public LlmProvider generationProviderOrDefault() {
		return generationProvider == null ? provider : generationProvider;
	}

	public boolean sharesOneServer() {
		return embeddingProviderOrDefault() == generationProviderOrDefault();
	}
}
