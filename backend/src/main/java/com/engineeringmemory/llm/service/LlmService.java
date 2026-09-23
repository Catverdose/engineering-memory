package com.engineeringmemory.llm.service;

import java.util.List;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import com.engineeringmemory.aiconfig.config.LlmProperties;
import com.engineeringmemory.aiconfig.enums.LlmProvider;
import com.engineeringmemory.llm.client.EmbeddingClient;
import com.engineeringmemory.llm.client.GenerationClient;
import com.engineeringmemory.llm.dto.LlmRequest;
import com.engineeringmemory.llm.dto.LlmResponse;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class LlmService {

	private final EmbeddingClient embeddingClient;
	private final GenerationClient generationClient;

	public LlmService(
			List<EmbeddingClient> embeddingClients,
			List<GenerationClient> generationClients,
			LlmProperties properties) {

		this.embeddingClient = pick(
				"임베딩", embeddingClients, EmbeddingClient::provider, properties.embeddingProviderOrDefault());
		this.generationClient = pick(
				"생성", generationClients, GenerationClient::provider, properties.generationProviderOrDefault());

		if (properties.sharesOneServer()) {
			log.info("모델 서버 선택: provider={}, embeddingModel={}, generationModel={}",
					embeddingClient.provider(), embeddingClient.embeddingModel(),
					generationClient.generationModel());
		} else {
			log.info("모델 서버를 역할별로 나눠 씁니다: embedding={}({}), generation={}({})",
					embeddingClient.provider(), embeddingClient.embeddingModel(),
					generationClient.provider(), generationClient.generationModel());
		}
	}

	private static <T> T pick(String role, List<T> candidates,
			java.util.function.Function<T, LlmProvider> providerOf, LlmProvider wanted) {

		List<T> matching = candidates.stream()
				.filter(c -> providerOf.apply(c) == wanted)
				.toList();

		if (matching.isEmpty()) {
			throw new IllegalStateException(
					"%s 을(를) 맡을 provider=%s 구현이 없습니다. 등록된 provider: %s"
							.formatted(role, wanted, candidates.stream().map(providerOf).toList()));
		}
		if (matching.size() > 1) {
			throw new IllegalStateException(
					"%s 을(를) 맡을 provider=%s 구현이 %d개입니다. 하나만 등록해야 합니다."
							.formatted(role, wanted, matching.size()));
		}
		return matching.get(0);
	}

	public List<float[]> embed(List<String> inputs) {
		return embeddingClient.embed(inputs);
	}

	public LlmResponse generate(LlmRequest request) {
		return generationClient.generate(request);
	}

	public LlmResponse generateStream(LlmRequest request, Consumer<String> onDelta) {
		return generationClient.generateStream(request, onDelta);
	}

	public boolean isAvailable() {
		if (embeddingClient == generationClient) {
			return isEmbeddingAvailable();
		}
		return isEmbeddingAvailable() && isGenerationAvailable();
	}

	public boolean isEmbeddingAvailable() {
		return embeddingClient.isEmbeddingAvailable();
	}

	public boolean isGenerationAvailable() {
		return generationClient.isGenerationAvailable();
	}

	public String embeddingModel() {
		return embeddingClient.embeddingModel();
	}

	public String generationModel() {
		return generationClient.generationModel();
	}
}
