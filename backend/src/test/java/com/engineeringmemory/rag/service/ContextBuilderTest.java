package com.engineeringmemory.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.engineeringmemory.aiconfig.config.OllamaProperties;
import com.engineeringmemory.aiconfig.config.RagProperties;
import com.engineeringmemory.conversation.service.ConversationService.HistoryMessage;
import com.engineeringmemory.knowledge.dto.ChunkHit;
import com.engineeringmemory.knowledge.enums.DocumentType;

class ContextBuilderTest {

	@Test
	@DisplayName("시스템·질문·지식·대화를 합친 최종 프롬프트가 단일 상한을 넘지 않는다")
	void capsTheWholePromptAndKeepsKnowledgeBeforeHistory() {
		ContextBuilder builder = builder(2_048, 8_192, 1_024);
		ChunkHit primary = hit(1L, "핵심 근거", "K".repeat(2_000));
		List<HistoryMessage> history = List.of(
				new HistoryMessage("USER", "OLD_HISTORY".repeat(200)),
				new HistoryMessage("ASSISTANT", "LATEST_HISTORY".repeat(200)));

		var context = builder.build("원인이 무엇인가요?", List.of(primary), history);

		assertThat(context.prompt()).hasSizeLessThanOrEqualTo(2_048);
		assertThat(context.prompt()).contains("[System]", "[Personal Knowledge]", "[User Question]");
		assertThat(context.prompt()).contains("KKKKKKKKKK");
		assertThat(context.prompt()).doesNotContain("OLD_HISTORY", "LATEST_HISTORY");
		assertThat(context.sources()).containsExactly(primary);
	}

	@Test
	@DisplayName("프롬프트에 실제로 들어간 근거만 출처로 반환한다")
	void returnsOnlySourcesActuallyIncludedInPrompt() {
		ContextBuilder builder = builder(2_048, 8_192, 1_024);
		List<ChunkHit> hits = List.of(
				hit(1L, "첫 문서", "A".repeat(900)),
				hit(2L, "둘째 문서", "B".repeat(900)),
				hit(3L, "셋째 문서", "C".repeat(900)));

		var context = builder.build("설명해 주세요.", hits, List.of());

		assertThat(context.prompt()).hasSizeLessThanOrEqualTo(2_048);
		assertThat(context.sources()).isNotEmpty().hasSizeLessThan(hits.size());
		for (ChunkHit source : context.sources()) {
			assertThat(context.prompt()).contains(source.title());
		}
		assertThat(context.sources()).doesNotContain(hits.get(2));
	}

	@Test
	@DisplayName("남은 공간에는 오래된 기록보다 최신 대화를 우선한다")
	void prefersRecentHistoryInRemainingBudget() {
		ContextBuilder builder = builder(3_000, 16_384, 1_024);
		List<HistoryMessage> history = List.of(
				new HistoryMessage("USER", "OLDEST_" + "O".repeat(1_500)),
				new HistoryMessage("ASSISTANT", "MIDDLE_" + "M".repeat(1_500)),
				new HistoryMessage("USER", "NEWEST_" + "N".repeat(1_500)));

		var context = builder.build("후속 질문", List.of(hit(1L, "근거", "사실")), history);

		assertThat(context.prompt()).hasSizeLessThanOrEqualTo(3_000);
		assertThat(context.prompt()).contains("NEWEST_");
		assertThat(context.prompt()).doesNotContain("OLDEST_");
	}

	@Test
	@DisplayName("문자 상한의 최악 토큰 수가 num_ctx를 넘으면 기동 단계에서 거부한다")
	void rejectsUnsafeBudgetCombination() {
		assertThatThrownBy(() -> builder(10_000, 8_192, 1_024))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Ollama 컨텍스트를 초과");
	}

	private static ContextBuilder builder(int maxPromptChars, int numCtx, int numPredict) {
		RagProperties rag = new RagProperties(5, 0.55, maxPromptChars, 3);
		OllamaProperties ollama = new OllamaProperties(
				"http://localhost", "embed", "generate", 1024, 0.2,
				numCtx, numPredict, Duration.ofSeconds(5), Duration.ofSeconds(180));
		return new ContextBuilder(rag, ollama);
	}

	private static ChunkHit hit(long id, String title, String content) {
		return new ChunkHit(id, id, title, DocumentType.NOTE,
				List.of("project"), List.of("java"), List.of(), null,
				0, null, content, 0.9);
	}
}
