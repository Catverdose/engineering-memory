package com.engineeringmemory.llm.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;

import com.engineeringmemory.llm.service.LlmService;

class LlmHealthIndicatorTest {

	private final LlmService llmService = mock(LlmService.class);
	private final LlmHealthIndicator indicator = new LlmHealthIndicator(llmService);

	@Test
	void reportsUpOnlyWhenRequiredModelsAreAvailable() {
		when(llmService.isAvailable()).thenReturn(true);
		when(llmService.embeddingModel()).thenReturn("bge-m3");
		when(llmService.generationModel()).thenReturn("exaone3.5:7.8b");

		Health health = indicator.health();

		assertThat(health.getStatus().getCode()).isEqualTo("UP");
		assertThat(health.getDetails()).containsEntry("embeddingModel", "bge-m3")
				.containsEntry("generationModel", "exaone3.5:7.8b");
	}

	@Test
	void reportsDownWhenRequiredModelIsMissing() {
		when(llmService.isAvailable()).thenReturn(false);

		Health health = indicator.health();

		assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
	}

	@Test
	void reportsDownWhenAvailabilityProbeFails() {
		when(llmService.isAvailable()).thenThrow(new IllegalStateException("probe failed"));

		Health health = indicator.health();

		assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
	}
}
