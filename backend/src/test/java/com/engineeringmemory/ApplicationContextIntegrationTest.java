package com.engineeringmemory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import com.engineeringmemory.aiconfig.config.OllamaProperties;
import com.engineeringmemory.aiconfig.config.RagProperties;
import com.engineeringmemory.auth.repository.UserAccountRepository;
import com.engineeringmemory.chat.controller.ChatController;
import com.engineeringmemory.conversation.controller.ConversationController;
import com.engineeringmemory.knowledge.config.KnowledgeProperties;
import com.engineeringmemory.knowledge.controller.DocumentController;
import com.engineeringmemory.knowledge.repository.DocumentChunkVectorRepository;
import com.engineeringmemory.traffic.config.RateLimitProperties;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationContextIntegrationTest {

	@Autowired
	private ApplicationContext context;

	@Test
	@DisplayName("개인 지식 서비스의 핵심 빈이 실제 컨텍스트에 등록된다")
	void contextLoads_andRegistersKeyBeans() {
		assertThat(context.getBean(ChatController.class)).isNotNull();
		assertThat(context.getBean(DocumentController.class)).isNotNull();
		assertThat(context.getBean(ConversationController.class)).isNotNull();
		assertThat(context.getBean(DocumentChunkVectorRepository.class)).isNotNull();
		assertThat(context.getBean(UserAccountRepository.class)).isNotNull();
	}

	@Test
	@DisplayName("개인 지식 검색과 업로드 설정이 YAML 값으로 바인딩된다")
	void configurationProperties_areBoundFromApplicationYaml() {
		OllamaProperties ollama = context.getBean(OllamaProperties.class);
		assertThat(ollama.embeddingModel()).isEqualTo("bge-m3");
		assertThat(ollama.embeddingDimensions()).isEqualTo(1024);
		assertThat(ollama.numCtx()).isEqualTo(32768);
		assertThat(ollama.numPredict()).isEqualTo(1024);

		RagProperties rag = context.getBean(RagProperties.class);
		assertThat(rag.topK()).isEqualTo(5);
		assertThat(rag.maxPromptChars()).isEqualTo(10000);
		assertThat(rag.maxChunksPerDocument()).isEqualTo(3);

		KnowledgeProperties knowledge = context.getBean(KnowledgeProperties.class);
		assertThat(knowledge.maxFileBytes()).isEqualTo(20L * 1024 * 1024);
		assertThat(knowledge.maxConcurrentIndexing()).isEqualTo(2);

		RateLimitProperties rateLimit = context.getBean(RateLimitProperties.class);
		assertThat(rateLimit.enabled()).isTrue();
	}
}
