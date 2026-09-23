package com.engineeringmemory.llm.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.engineeringmemory.aiconfig.config.ModelObservabilityProperties;
import com.engineeringmemory.aiconfig.config.OllamaProperties;
import com.engineeringmemory.llm.observability.ModelCallStats;
import com.engineeringmemory.llm.dto.LlmRequest;

import tools.jackson.databind.ObjectMapper;

class OllamaClientTest {

	@Test
	@DisplayName("생성 요청에 num_ctx와 num_predict를 항상 명시한다")
	void sendsExplicitContextAndOutputLimits() {
		RestClient.Builder restClient = RestClient.builder().baseUrl("http://ollama.test");
		MockRestServiceServer server = MockRestServiceServer.bindTo(restClient).build();
		OllamaClient client = new OllamaClient(restClient.build(), properties(), new ObjectMapper(), stats());
		server.expect(requestTo("http://ollama.test/api/generate"))
				.andExpect(method(POST))
				.andExpect(content().string(containsString("\"num_ctx\":32768")))
				.andExpect(content().string(containsString("\"num_predict\":1024")))
				.andRespond(withSuccess("""
						{"response":"답변","done":true,"eval_count":2,"total_duration":1000000}
						""", MediaType.APPLICATION_JSON));

		assertThat(client.generate(LlmRequest.of("프롬프트")).text()).isEqualTo("답변");
		server.verify();
	}

	@Test
	@DisplayName("Ollama가 응답해도 필수 모델 두 개가 모두 설치되어야 가용하다")
	void requiresBothInstalledModels() {
		assertThat(OllamaClient.hasRequiredModels(
				List.of("bge-m3:latest", "exaone3.5:7.8b"),
				"bge-m3", "exaone3.5:7.8b")).isTrue();
		assertThat(OllamaClient.hasRequiredModels(
				List.of("bge-m3:latest"),
				"bge-m3", "exaone3.5:7.8b")).isFalse();
	}

	@Test
	@DisplayName("tags 응답의 실제 모델 목록으로 가용성을 판정한다")
	void readsInstalledModelsFromTagsResponse() {
		RestClient.Builder restClient = RestClient.builder().baseUrl("http://ollama.test");
		MockRestServiceServer server = MockRestServiceServer.bindTo(restClient).build();
		OllamaClient client = new OllamaClient(restClient.build(), properties(), new ObjectMapper(), stats());
		server.expect(ExpectedCount.twice(), requestTo("http://ollama.test/api/tags"))
				.andRespond(withSuccess("""
						{"models":[
						  {"name":"bge-m3:latest","model":"bge-m3:latest","size":1},
						  {"name":"exaone3.5:7.8b","model":"exaone3.5:7.8b","size":1}
						]}
						""", MediaType.APPLICATION_JSON));

		assertThat(client.isEmbeddingAvailable()).isTrue();
		assertThat(client.isGenerationAvailable()).isTrue();
		server.verify();
	}

	private static ModelCallStats stats() {
		return new ModelCallStats(new ModelObservabilityProperties(
				Duration.ofSeconds(2), Duration.ofSeconds(60)));
	}

	private static OllamaProperties properties() {
		return new OllamaProperties(
				"http://ollama.test", "bge-m3", "exaone3.5:7.8b", 1024, 0.2,
				32_768, 1_024, Duration.ofSeconds(5), Duration.ofSeconds(180));
	}
}
