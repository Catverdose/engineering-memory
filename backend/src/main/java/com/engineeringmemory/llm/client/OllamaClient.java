package com.engineeringmemory.llm.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.engineeringmemory.aiconfig.config.OllamaProperties;
import com.engineeringmemory.aiconfig.enums.LlmProvider;
import com.engineeringmemory.common.exception.BusinessException;
import com.engineeringmemory.common.exception.ErrorCode;
import com.engineeringmemory.llm.dto.LlmRequest;
import com.engineeringmemory.llm.observability.ModelCallStats;
import com.engineeringmemory.llm.observability.ServerTiming;
import com.engineeringmemory.llm.dto.LlmResponse;

import tools.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class OllamaClient implements EmbeddingClient, GenerationClient {

	private final RestClient ollamaRestClient;
	private final OllamaProperties properties;
	private final ObjectMapper objectMapper;
	private final ModelCallStats stats;

	public OllamaClient(
			@Qualifier("ollamaRestClient") RestClient ollamaRestClient,
			OllamaProperties properties,
			ObjectMapper objectMapper,
			ModelCallStats stats) {
		this.ollamaRestClient = ollamaRestClient;
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.stats = stats;
	}

	@Override
	public LlmProvider provider() {
		return LlmProvider.OLLAMA;
	}

	@Override
	public List<float[]> embed(List<String> inputs) {
		if (inputs == null || inputs.isEmpty()) {
			return List.of();
		}

		EmbedResponse response;
		ModelCallStats.Ticket ticket = stats.start(ModelCallStats.Role.EMBEDDING);
		try {
			response = ollamaRestClient.post()
					.uri("/api/embed")
					.contentType(MediaType.APPLICATION_JSON)
					.body(new EmbedRequest(properties.embeddingModel(), inputs))
					.retrieve()
					.body(EmbedResponse.class);
		} catch (RestClientException e) {
			ticket.abort();
			throw new BusinessException(ErrorCode.MODEL_UNAVAILABLE,
					"임베딩 모델 호출에 실패했습니다. model=" + properties.embeddingModel(), e);
		}
		ticket.finish(response == null ? null : response.timing());

		if (response == null || response.embeddings() == null
				|| response.embeddings().size() != inputs.size()) {
			throw new BusinessException(ErrorCode.MODEL_UNAVAILABLE,
					"임베딩 응답 개수가 요청과 다릅니다. model=" + properties.embeddingModel());
		}

		return response.embeddings().stream().map(this::toValidatedVector).toList();
	}

	@Override
	public LlmResponse generate(LlmRequest request) {
		GenerateResponse response;
		ModelCallStats.Ticket ticket = stats.start(ModelCallStats.Role.GENERATION);
		try {
			response = ollamaRestClient.post()
					.uri("/api/generate")
					.contentType(MediaType.APPLICATION_JSON)
					.body(new GenerateRequest(
							properties.generationModel(),
							request.prompt(),
							false,
							optionsOf(request)))
					.retrieve()
					.body(GenerateResponse.class);
		} catch (RestClientException e) {
			ticket.abort();
			throw new BusinessException(ErrorCode.MODEL_UNAVAILABLE,
					"생성 모델 호출에 실패했습니다. model=" + properties.generationModel(), e);
		}
		ticket.finish(response == null ? null : response.timing());

		if (response == null || response.response() == null || response.response().isBlank()) {
			throw new BusinessException(ErrorCode.MODEL_UNAVAILABLE,
					"생성 응답이 비어 있습니다. model=" + properties.generationModel());
		}

		log.debug("생성 완료: evalCount={}, durationMs={}", response.evalCount(), response.durationMs());
		return new LlmResponse(response.response(), response.evalCount(), response.durationMs());
	}

	@Override
	public LlmResponse generateStream(LlmRequest request, Consumer<String> onDelta) {
		StringBuilder full = new StringBuilder(512);
		GenerateResponse[] finalChunk = new GenerateResponse[1];
		ModelCallStats.Ticket ticket = stats.start(ModelCallStats.Role.GENERATION);
		try {
			ollamaRestClient.post()
					.uri("/api/generate")
					.contentType(MediaType.APPLICATION_JSON)
					.body(new GenerateRequest(
							properties.generationModel(),
							request.prompt(),
							true,
							optionsOf(request)))
					.exchange((req, response) -> {
						if (response.getStatusCode().isError()) {
							throw new BusinessException(ErrorCode.MODEL_UNAVAILABLE,
									"생성 모델이 오류를 반환했습니다. status=" + response.getStatusCode());
						}
						finalChunk[0] = readNdjson(response.getBody(), onDelta, full);
						return null;
					});
			ticket.finish(finalChunk[0] == null ? null : finalChunk[0].timing());
		} catch (BusinessException e) {
			throw e;
		} catch (DeltaConsumerException e) {
			throw e.unwrap();
		} catch (RestClientException | UncheckedIOException e) {
			throw new BusinessException(ErrorCode.MODEL_UNAVAILABLE,
					"생성 모델 스트리밍 호출에 실패했습니다. model=" + properties.generationModel(), e);
		} finally {
			ticket.abort();
		}

		if (full.isEmpty()) {
			throw new BusinessException(ErrorCode.MODEL_UNAVAILABLE,
					"생성 응답이 비어 있습니다. model=" + properties.generationModel());
		}

		GenerateResponse last = finalChunk[0];
		return new LlmResponse(full.toString(),
				last == null ? null : last.evalCount(),
				last == null ? null : last.durationMs());
	}

	private double temperatureOf(LlmRequest request) {
		return request.hasTemperature() ? request.temperature() : properties.temperature();
	}

	private Options optionsOf(LlmRequest request) {
		return new Options(temperatureOf(request), properties.numCtx(), properties.numPredict());
	}

	private GenerateResponse readNdjson(InputStream body, Consumer<String> onDelta, StringBuilder full) {
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(body, StandardCharsets.UTF_8))) {

			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				GenerateResponse chunk = objectMapper.readValue(line, GenerateResponse.class);

				if (chunk.response() != null && !chunk.response().isEmpty()) {
					full.append(chunk.response());
					try {
						onDelta.accept(chunk.response());
					} catch (RuntimeException e) {
						throw new DeltaConsumerException(e);
					}
				}
				if (Boolean.TRUE.equals(chunk.done())) {
					log.debug("스트리밍 생성 완료: evalCount={}", chunk.evalCount());
					return chunk;
				}
			}
			throw new BusinessException(ErrorCode.MODEL_UNAVAILABLE,
					"생성이 완료 신호 없이 중단되었습니다.");

		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Override
	public boolean isEmbeddingAvailable() {
		return hasModels();
	}

	@Override
	public boolean isGenerationAvailable() {
		return hasModels();
	}

	private boolean hasModels() {
		try {
			TagsResponse response = ollamaRestClient.get()
					.uri("/api/tags")
					.retrieve()
					.body(TagsResponse.class);
			List<String> installed = installedModelNames(response);
			boolean available = hasRequiredModels(
					installed, properties.embeddingModel(), properties.generationModel());
			if (!available) {
				log.warn("Ollama 필수 모델 누락: embeddingModel={}, generationModel={}, installed={}",
						properties.embeddingModel(), properties.generationModel(), installed);
			}
			return available;
		} catch (RestClientException e) {
			log.warn("Ollama 응답 없음: baseUrl={}, reason={}", properties.baseUrl(), e.getMessage());
			return false;
		}
	}

	private static List<String> installedModelNames(TagsResponse response) {
		if (response == null || response.models() == null) {
			return List.of();
		}
		List<String> names = new ArrayList<>(response.models().size() * 2);
		for (ModelInfo model : response.models()) {
			if (model == null) {
				continue;
			}
			if (model.name() != null && !model.name().isBlank()) {
				names.add(model.name());
			}
			if (model.model() != null && !model.model().isBlank()
					&& !model.model().equals(model.name())) {
				names.add(model.model());
			}
		}
		return List.copyOf(names);
	}

	static boolean hasRequiredModels(
			List<String> installedModels, String embeddingModel, String generationModel) {
		if (installedModels == null) {
			return false;
		}
		return hasModel(installedModels, embeddingModel)
				&& hasModel(installedModels, generationModel);
	}

	private static boolean hasModel(List<String> installedModels, String required) {
		if (required == null || required.isBlank()) {
			return false;
		}
		String exact = required.strip();
		String withDefaultTag = exact.indexOf(':') < 0 ? exact + ":latest" : exact;
		return installedModels.stream()
				.filter(name -> name != null)
				.map(String::strip)
				.anyMatch(name -> name.equals(exact) || name.equals(withDefaultTag));
	}

	@Override
	public String embeddingModel() {
		return properties.embeddingModel();
	}

	@Override
	public String generationModel() {
		return properties.generationModel();
	}

	private float[] toValidatedVector(List<Double> values) {
		int expected = properties.embeddingDimensions();
		if (values == null || values.size() != expected) {
			throw new BusinessException(ErrorCode.MODEL_UNAVAILABLE,
					"임베딩 차원이 %d 이어야 하는데 %s 입니다. 모델과 설정이 맞는지 확인해 주세요."
							.formatted(expected, values == null ? "null" : values.size()));
		}
		float[] vector = new float[values.size()];
		for (int i = 0; i < values.size(); i++) {
			vector[i] = values.get(i).floatValue();
		}
		return vector;
	}

	private static final class DeltaConsumerException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		private DeltaConsumerException(RuntimeException cause) {
			super(cause);
		}

		private RuntimeException unwrap() {
			return (RuntimeException) getCause();
		}
	}

	private record EmbedRequest(String model, List<String> input) {
	}

	private record EmbedResponse(
			List<List<Double>> embeddings,
			@JsonProperty("total_duration") Long totalDuration,
			@JsonProperty("load_duration") Long loadDuration,
			@JsonProperty("prompt_eval_duration") Long promptEvalDuration) {

		ServerTiming timing() {
			return ServerTiming.ofNanos(totalDuration, loadDuration, promptEvalDuration, 0L);
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record TagsResponse(List<ModelInfo> models) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record ModelInfo(String name, String model) {
	}

	private record GenerateRequest(String model, String prompt, boolean stream, Options options) {
	}

	private record Options(
			double temperature,
			@JsonProperty("num_ctx") int numCtx,
			@JsonProperty("num_predict") int numPredict) {
	}

	private record GenerateResponse(
			String response,
			Boolean done,
			@JsonProperty("eval_count") Integer evalCount,
			@JsonProperty("total_duration") Long totalDuration,
			@JsonProperty("load_duration") Long loadDuration,
			@JsonProperty("prompt_eval_duration") Long promptEvalDuration,
			@JsonProperty("eval_duration") Long evalDuration) {

		ServerTiming timing() {
			return ServerTiming.ofNanos(totalDuration, loadDuration, promptEvalDuration, evalDuration);
		}

		Long durationMs() {
			return totalDuration == null ? null : totalDuration / 1_000_000;
		}
	}
}
