package com.engineeringmemory.application.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.engineeringmemory.chat.dto.request.ChatRequest;
import com.engineeringmemory.chat.dto.response.ChatResponse;
import com.engineeringmemory.chat.dto.response.ChatResponse.Source;
import com.engineeringmemory.chat.enums.ChatStatus;
import com.engineeringmemory.common.exception.BusinessException;
import com.engineeringmemory.common.exception.ErrorCode;
import com.engineeringmemory.conversation.service.ConversationService;
import com.engineeringmemory.conversation.service.ConversationService.HistoryMessage;
import com.engineeringmemory.conversation.service.ConversationService.Turn;
import com.engineeringmemory.knowledge.dto.ChunkHit;
import com.engineeringmemory.knowledge.enums.DocumentType;
import com.engineeringmemory.llm.dto.LlmRequest;
import com.engineeringmemory.llm.dto.LlmResponse;
import com.engineeringmemory.llm.service.LlmService;
import com.engineeringmemory.rag.dto.RagContext;
import com.engineeringmemory.rag.dto.RetrievalResult;
import com.engineeringmemory.rag.service.RagService;

class ChatOrchestratorTest {

	@Test
	@DisplayName("근거가 없으면 생성 모델을 아예 호출하지 않는다")
	void neverCallsModelWithoutContext() {
		Fixture fixture = new Fixture();
		fixture.retrieves(RetrievalResult.empty());

		fixture.orchestrator.narrate(42L, "req-1", question(), fixture.sink);

		verifyNoInteractions(fixture.llm);
	}

	@Test
	@DisplayName("근거가 없으면 빈 근거 목록과 모델 없음을 함께 알린다")
	void reportsEmptySourcesAndNoModel() {
		Fixture fixture = new Fixture();
		fixture.retrieves(RetrievalResult.empty());

		ChatResponse response = fixture.orchestrator.answer(42L, "req-1", question());

		assertThat(response.status()).isEqualTo(ChatStatus.NO_CONTEXT);
		assertThat(response.sources())
				.as("근거가 있다고 표시하면 사용자가 확인할 수 없는 출처를 믿게 된다")
				.isEmpty();
		assertThat(response.answer()).isEqualTo(ErrorCode.NO_CONTEXT.getDefaultMessage());
	}

	@Test
	@DisplayName("근거 없음도 대화에 남긴다")
	void recordsNoContextTurn() {
		Fixture fixture = new Fixture();
		fixture.retrieves(RetrievalResult.empty());

		fixture.orchestrator.narrate(42L, "req-1", question(), fixture.sink);

		verify(fixture.conversations).completeTurn(
				eq(42L), eq(8L), eq(ErrorCode.NO_CONTEXT.getDefaultMessage()), eq(List.of()), eq(true));
	}

	@Test
	@DisplayName("응답의 근거는 모델이 실제로 본 청크와 같다")
	void sourcesMatchWhatTheModelActuallySaw() {
		Fixture fixture = new Fixture();
		ChunkHit seen = chunk(11L, 101L, "Nginx SSE 버퍼링");
		ChunkHit alsoSeen = chunk(12L, 102L, "프록시 설정 정리");
		fixture.retrieves(RetrievalResult.of(List.of(seen, alsoSeen)));
		fixture.buildsContext(new RagContext("프롬프트", List.of(seen)));

		ChatResponse response = fixture.orchestrator.answer(42L, "req-1", question());

		assertThat(response.sources())
				.extracting(Source::chunkId)
				.as("검색 결과가 아니라 모델이 본 것을 보여줘야 한다")
				.containsExactly(11L);
	}

	@Test
	@DisplayName("첫 질문은 그대로 검색한다")
	void retrievalQueryIsUntouchedWithoutHistory() {
		assertThat(ChatOrchestrator.retrievalQuery("Redis 왜 썼지?", List.of()))
				.isEqualTo("Redis 왜 썼지?");
		assertThat(ChatOrchestrator.retrievalQuery("Redis 왜 썼지?", null))
				.isEqualTo("Redis 왜 썼지?");
	}

	@Test
	@DisplayName("후속 질문은 최근 대화를 붙여 검색한다")
	void retrievalQueryAppendsRecentHistoryFirst() {
		List<HistoryMessage> history = List.of(
				new HistoryMessage("user", "PetCoupon 에서 Redis 를 왜 썼지?"),
				new HistoryMessage("assistant", "쿠폰 재고를 Lua 로 원자적으로 깎으려고 썼다"));

		String query = ChatOrchestrator.retrievalQuery("그때 Kafka 는?", history);

		assertThat(query).startsWith("그때 Kafka 는?");
		assertThat(query).contains("PetCoupon", "Lua");

		assertThat(query.indexOf("Lua")).isLessThan(query.indexOf("PetCoupon"));
	}

	@Test
	@DisplayName("대화가 길어도 검색어는 상한을 넘지 않는다")
	void retrievalQueryStaysBounded() {
		List<HistoryMessage> history = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			history.add(new HistoryMessage("user", "가".repeat(500)));
		}

		String query = ChatOrchestrator.retrievalQuery("질문", history);

		assertThat(query.length()).isLessThanOrEqualTo(2400 + 500);
	}

	@Test
	@DisplayName("프롬프트 조립에도 대화 기록을 넘긴다")
	void passesHistoryIntoPromptBuilding() {
		Fixture fixture = new Fixture();
		List<HistoryMessage> history = List.of(new HistoryMessage("user", "앞선 질문"));
		fixture.withHistory(history);
		ChunkHit hit = chunk(11L, 101L, "문서");
		fixture.retrieves(RetrievalResult.of(List.of(hit)));
		fixture.buildsContext(new RagContext("프롬프트", List.of(hit)));

		fixture.orchestrator.narrate(42L, "req-1", question(), fixture.sink);

		verify(fixture.rag).buildContext(eq("질문"), eq(List.of(hit)), eq(history));
	}

	@Test
	@DisplayName("스트리밍이든 아니든 같은 상태와 같은 근거를 낸다")
	void transportDoesNotChangeDecision() {
		ChunkHit hit = chunk(11L, 101L, "문서");

		Fixture nonStreaming = new Fixture();
		nonStreaming.retrieves(RetrievalResult.of(List.of(hit)));
		nonStreaming.buildsContext(new RagContext("프롬프트", List.of(hit)));
		ChatResponse collected = nonStreaming.orchestrator.answer(42L, "req-1", question());

		Fixture streaming = new Fixture();
		streaming.retrieves(RetrievalResult.of(List.of(hit)));
		streaming.buildsContext(new RagContext("프롬프트", List.of(hit)));
		RecordingSink sink = new RecordingSink(true);
		streaming.orchestrator.narrate(42L, "req-1", question(), sink);

		assertThat(sink.doneReason).isEqualTo(collected.status());
		assertThat(sink.sources).extracting(Source::chunkId)
				.isEqualTo(collected.sources().stream().map(Source::chunkId).toList());
	}

	@Test
	@DisplayName("조각으로 받는 통로에만 스트리밍 생성을 쓴다")
	void usesStreamingGenerationOnlyForIncrementalSinks() {
		ChunkHit hit = chunk(11L, 101L, "문서");

		Fixture streaming = new Fixture();
		streaming.retrieves(RetrievalResult.of(List.of(hit)));
		streaming.buildsContext(new RagContext("프롬프트", List.of(hit)));
		streaming.orchestrator.narrate(42L, "req-1", question(), new RecordingSink(true));

		verify(streaming.llm).generateStream(any(LlmRequest.class), any());
		verify(streaming.llm, never()).generate(any(LlmRequest.class));

		Fixture blocking = new Fixture();
		blocking.retrieves(RetrievalResult.of(List.of(hit)));
		blocking.buildsContext(new RagContext("프롬프트", List.of(hit)));
		blocking.orchestrator.narrate(42L, "req-1", question(), new RecordingSink(false));

		verify(blocking.llm).generate(any(LlmRequest.class));
		verify(blocking.llm, never()).generateStream(any(LlmRequest.class), any());
	}

	@Test
	@DisplayName("생성된 답변을 그대로 대화에 기록한다")
	void persistsGeneratedAnswer() {
		Fixture fixture = new Fixture();
		ChunkHit hit = chunk(11L, 101L, "문서");
		fixture.retrieves(RetrievalResult.of(List.of(hit)));
		fixture.buildsContext(new RagContext("프롬프트", List.of(hit)));
		fixture.streamsDeltas("프록", "시 버퍼링을 껐다");

		fixture.orchestrator.narrate(42L, "req-1", question(), new RecordingSink(true));

		ArgumentCaptor<String> answer = ArgumentCaptor.forClass(String.class);
		verify(fixture.conversations).completeTurn(
				anyLong(), anyLong(), answer.capture(), any(), eq(false));

		assertThat(answer.getValue()).isEqualTo("프록시 버퍼링을 껐다");
	}

	@Test
	@DisplayName("검색 실패 뒤에도 생성된 conversationId를 예외에 보존하고 실패 상태로 기록한다")
	void preservesConversationIdOnRetrievalFailure() {
		Fixture fixture = new Fixture();
		when(fixture.rag.retrieve(eq(42L), any(), any()))
				.thenThrow(new BusinessException(ErrorCode.MODEL_UNAVAILABLE));

		assertThatThrownBy(() -> fixture.orchestrator.narrate(42L, "req-1", question(), fixture.sink))
				.isInstanceOfSatisfying(ChatTurnException.class,
						error -> assertThat(error.conversationId()).isEqualTo(7L));

		verify(fixture.conversations).failTurn(42L, 8L);
	}

	@Test
	@DisplayName("답변 저장 뒤 DONE 전송만 실패하면 완료 메시지를 FAILED로 되돌리지 않는다")
	void doesNotOverwritePersistedTurnWhenTerminalFrameFails() {
		Fixture fixture = new Fixture();
		fixture.retrieves(RetrievalResult.empty());
		doThrow(new RuntimeException("disconnected")).when(fixture.sink).done(ChatStatus.NO_CONTEXT);

		assertThatThrownBy(() -> fixture.orchestrator.narrate(42L, "req-1", question(), fixture.sink))
				.isInstanceOf(ChatTurnException.class);

		verify(fixture.conversations).completeTurn(eq(42L), eq(8L), any(), eq(List.of()), eq(true));
		verify(fixture.conversations, never()).failTurn(anyLong(), anyLong());
	}

	private static ChatRequest question() {
		return new ChatRequest(null, "질문", null);
	}

	private static ChunkHit chunk(long chunkId, long documentId, String title) {
		return new ChunkHit(chunkId, documentId, title, DocumentType.TROUBLESHOOTING,
				List.of("ubot"), List.of("nginx"), List.of(),
				LocalDate.of(2026, 1, 1), 0, "heading", "본문", 0.81);
	}

	private static final class RecordingSink implements AnswerSink {
		private final boolean incremental;
		final StringBuilder text = new StringBuilder();
		List<Source> sources = List.of();
		ChatStatus doneReason;

		RecordingSink(boolean incremental) {
			this.incremental = incremental;
		}

		@Override
		public boolean incremental() {
			return incremental;
		}

		@Override
		public void meta(Long conversationId, String scope, List<Source> sources, String model) {
			this.sources = sources;
		}

		@Override
		public void delta(String delta) {
			text.append(delta);
		}

		@Override
		public void done(ChatStatus reason) {
			this.doneReason = reason;
		}
	}

	private static final class Fixture {
		final RagService rag = mock(RagService.class);
		final LlmService llm = mock(LlmService.class);
		final ConversationService conversations = mock(ConversationService.class);
		final AnswerSink sink = mock(AnswerSink.class);
		final ChatOrchestrator orchestrator = new ChatOrchestrator(rag, llm, conversations);

		Fixture() {
			withHistory(List.of());
			when(llm.generationModel()).thenReturn("exaone3.5:7.8b");
			when(llm.generate(any(LlmRequest.class))).thenReturn(LlmResponse.of("답변"));
		}

		void withHistory(List<HistoryMessage> history) {
			when(conversations.beginTurn(42L, null, "질문", "req-1"))
					.thenReturn(new Turn(7L, 8L, history));
		}

		void retrieves(RetrievalResult result) {
			when(rag.retrieve(eq(42L), any(), any())).thenReturn(result);
		}

		void buildsContext(RagContext context) {
			when(rag.buildContext(any(), any(), any())).thenReturn(context);
		}

		void streamsDeltas(String... deltas) {
			when(llm.generateStream(any(LlmRequest.class), any())).thenAnswer(invocation -> {
				Consumer<String> onDelta = invocation.getArgument(1);
				for (String delta : deltas) {
					onDelta.accept(delta);
				}
				return LlmResponse.of(String.join("", deltas));
			});
		}
	}
}
