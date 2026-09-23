package com.engineeringmemory.llm.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import com.engineeringmemory.llm.service.LlmService;

import lombok.RequiredArgsConstructor;

@Component("llm")
@RequiredArgsConstructor
public class LlmHealthIndicator implements HealthIndicator {

	private final LlmService llmService;

	@Override
	public Health health() {
		try {
			if (llmService.isAvailable()) {
				return Health.up()
						.withDetail("embeddingModel", llmService.embeddingModel())
						.withDetail("generationModel", llmService.generationModel())
						.build();
			}

			return Health.down()
					.withDetail("reason", "required models are unavailable")
					.withDetail("embedding", llmService.isEmbeddingAvailable() ? "up" : "down")
					.withDetail("generation", llmService.isGenerationAvailable() ? "up" : "down")
					.build();
		} catch (RuntimeException e) {
			return Health.down(e).build();
		}
	}
}
