package com.engineeringmemory.llm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.engineeringmemory.aiconfig.config.LlmProperties;
import com.engineeringmemory.aiconfig.enums.LlmProvider;
import com.engineeringmemory.llm.client.EmbeddingClient;
import com.engineeringmemory.llm.client.GenerationClient;
import com.engineeringmemory.llm.dto.LlmRequest;
import com.engineeringmemory.llm.dto.LlmResponse;

class LlmServiceTest {

	private record StubEmbedding(LlmProvider provider) implements EmbeddingClient {
		@Override
		public List<float[]> embed(List<String> inputs) {
			return List.of();
		}

		@Override
		public boolean isEmbeddingAvailable() {
			return true;
		}

		@Override
		public String embeddingModel() {
			return provider + "-embed";
		}
	}

	private record StubGeneration(LlmProvider provider) implements GenerationClient {
		@Override
		public LlmResponse generate(LlmRequest request) {
			return LlmResponse.of("답변");
		}

		@Override
		public LlmResponse generateStream(LlmRequest request, Consumer<String> onDelta) {
			onDelta.accept("답변");
			return LlmResponse.of("답변");
		}

		@Override
		public boolean isGenerationAvailable() {
			return true;
		}

		@Override
		public String generationModel() {
			return provider + "-gen";
		}
	}

	private static LlmService sharedServer(LlmProvider provider) {
		return new LlmService(
				List.of(new StubEmbedding(provider)),
				List.of(new StubGeneration(provider)),
				new LlmProperties(provider, null, null));
	}

	@Test
	@DisplayName("설정된 provider 에 맞는 구현을 고른다")
	void picksTheConfiguredProvider() {
		LlmService service = new LlmService(
				List.of(new StubEmbedding(LlmProvider.OLLAMA), new StubEmbedding(LlmProvider.VLLM)),
				List.of(new StubGeneration(LlmProvider.OLLAMA), new StubGeneration(LlmProvider.VLLM)),
				new LlmProperties(LlmProvider.VLLM, null, null));

		assertThat(service.generationModel()).isEqualTo("VLLM-gen");
		assertThat(service.embeddingModel()).isEqualTo("VLLM-embed");
	}

	@Test
	@DisplayName("임베딩과 생성을 서로 다른 서버로 보낼 수 있다")
	void allowsDifferentServerPerRole() {
		LlmService generationElsewhere = new LlmService(
				List.of(new StubEmbedding(LlmProvider.OLLAMA), new StubEmbedding(LlmProvider.VLLM)),
				List.of(new StubGeneration(LlmProvider.OLLAMA), new StubGeneration(LlmProvider.VLLM)),
				new LlmProperties(LlmProvider.OLLAMA, LlmProvider.OLLAMA, LlmProvider.VLLM));

		assertThat(generationElsewhere.embeddingModel()).isEqualTo("OLLAMA-embed");
		assertThat(generationElsewhere.generationModel()).isEqualTo("VLLM-gen");

		LlmService embeddingElsewhere = new LlmService(
				List.of(new StubEmbedding(LlmProvider.OLLAMA), new StubEmbedding(LlmProvider.VLLM)),
				List.of(new StubGeneration(LlmProvider.OLLAMA), new StubGeneration(LlmProvider.VLLM)),
				new LlmProperties(LlmProvider.OLLAMA, LlmProvider.VLLM, LlmProvider.OLLAMA));

		assertThat(embeddingElsewhere.embeddingModel()).isEqualTo("VLLM-embed");
		assertThat(embeddingElsewhere.generationModel()).isEqualTo("OLLAMA-gen");
	}

	@Test
	@DisplayName("역할별 값을 비우면 공통 provider 를 따른다")
	void roleProvidersDefaultToShared() {
		LlmService service = sharedServer(LlmProvider.OLLAMA);

		assertThat(service.embeddingModel()).isEqualTo("OLLAMA-embed");
		assertThat(service.generationModel()).isEqualTo("OLLAMA-gen");
	}

	@Test
	@DisplayName("임베딩을 맡을 구현이 없으면 기동에서 실패한다")
	void failsFastWhenNoEmbeddingClientMatches() {
		assertThatThrownBy(() -> new LlmService(
				List.of(new StubEmbedding(LlmProvider.OLLAMA)),
				List.of(new StubGeneration(LlmProvider.VLLM)),
				new LlmProperties(LlmProvider.VLLM, null, null)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("임베딩")
				.hasMessageContaining("VLLM")
				.hasMessageContaining("OLLAMA");
	}

	@Test
	@DisplayName("생성을 맡을 구현이 없으면 기동에서 실패한다")
	void failsFastWhenNoGenerationClientMatches() {
		assertThatThrownBy(() -> new LlmService(
				List.of(new StubEmbedding(LlmProvider.VLLM)),
				List.of(new StubGeneration(LlmProvider.OLLAMA)),
				new LlmProperties(LlmProvider.VLLM, null, null)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("생성");
	}

	@Test
	@DisplayName("같은 provider 구현이 둘이면 기동에서 실패한다")
	void failsFastWhenProviderIsAmbiguous() {
		assertThatThrownBy(() -> new LlmService(
				List.of(new StubEmbedding(LlmProvider.OLLAMA), new StubEmbedding(LlmProvider.OLLAMA)),
				List.of(new StubGeneration(LlmProvider.OLLAMA)),
				new LlmProperties(LlmProvider.OLLAMA, null, null)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("2개");
	}

	@Test
	@DisplayName("클라이언트가 아예 없어도 기동에서 실패한다")
	void failsFastWhenNoClientsAtAll() {
		assertThatThrownBy(() -> new LlmService(
				List.of(), List.of(), new LlmProperties(LlmProvider.OLLAMA, null, null)))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("호출은 고른 구현에 그대로 위임한다. 재시도나 폴백을 넣지 않는다")
	void delegatesWithoutRetryOrFallback() {
		LlmService service = sharedServer(LlmProvider.OLLAMA);

		assertThat(service.generate(LlmRequest.of("프롬프트")).text()).isEqualTo("답변");
		assertThat(service.isAvailable()).isTrue();
	}

	@Test
	@DisplayName("한 역할만 막혀도 사용 불가로 본다")
	void unavailableWhenEitherRoleIsDown() {
		LlmService embeddingDown = new LlmService(
				List.of(new EmbeddingClient() {
					@Override
					public LlmProvider provider() {
						return LlmProvider.OLLAMA;
					}

					@Override
					public List<float[]> embed(List<String> inputs) {
						return List.of();
					}

					@Override
					public boolean isEmbeddingAvailable() {
						return false;
					}

					@Override
					public String embeddingModel() {
						return "down";
					}
				}),
				List.of(new StubGeneration(LlmProvider.OLLAMA)),
				new LlmProperties(LlmProvider.OLLAMA, null, null));

		assertThat(embeddingDown.isEmbeddingAvailable()).isFalse();
		assertThat(embeddingDown.isGenerationAvailable()).isTrue();
		assertThat(embeddingDown.isAvailable()).as("한 쪽만 죽어도 서비스는 안 된다").isFalse();
	}
}
