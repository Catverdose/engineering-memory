package com.engineeringmemory.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.AsyncTaskExecutor;

import com.engineeringmemory.aiconfig.config.ModelConcurrencyProperties;
import com.engineeringmemory.llm.workload.ModelWorkloadGate;
import com.engineeringmemory.application.chat.AnswerSink;
import com.engineeringmemory.application.chat.ChatOrchestrator;
import com.engineeringmemory.application.chat.ChatTurnException;
import com.engineeringmemory.chat.dto.request.ChatRequest;
import com.engineeringmemory.chat.dto.response.ChatResponse;
import com.engineeringmemory.chat.dto.response.ChatResponse.Source;
import com.engineeringmemory.chat.enums.ChatStatus;
import com.engineeringmemory.common.exception.BusinessException;
import com.engineeringmemory.common.exception.ErrorCode;
import com.engineeringmemory.common.support.RequestId;
import com.engineeringmemory.streaming.service.StreamService;

class ChatServiceTest {

	private static final String MDC_REQUEST_ID = "request_id";

	@AfterEach
	void clearMdc() {
		MDC.clear();
	}

	@Test
	@DisplayName("정상 응답을 그대로 돌려주고 모델 자리를 반납한다")
	void chatReturnsOrchestratorResponseAndReleasesSlot() {
		Fixture fixture = new Fixture();
		fixture.answersWith(ChatResponse.completed("req", 3L, "전체", "답변", List.of()));

		assertThat(fixture.service.chat(1L, question()).status()).isEqualTo(ChatStatus.COMPLETED);

		assertThat(fixture.service.chat(1L, question()).status()).isEqualTo(ChatStatus.COMPLETED);
	}

	@Test
	@DisplayName("업무 예외는 해당 오류코드로 바꾸고 자리를 반납한다")
	void chatMapsBusinessExceptionAndReleasesSlot() {
		Fixture fixture = new Fixture();
		when(fixture.orchestrator.answer(anyLong(), anyString(), any()))
				.thenThrow(new BusinessException(ErrorCode.MODEL_UNAVAILABLE))
				.thenReturn(ChatResponse.completed("req", null, "전체", "답변", List.of()));

		ChatResponse failed = fixture.service.chat(1L, question());
		assertThat(failed.status()).isEqualTo(ChatStatus.FAILED);
		assertThat(failed.errorCode()).isEqualTo(ErrorCode.MODEL_UNAVAILABLE.name());
		assertThat(failed.retryable()).isTrue();

		assertThat(fixture.service.chat(1L, question()).status())
				.as("실패해도 자리는 돌아와야 한다")
				.isEqualTo(ChatStatus.COMPLETED);
	}

	@Test
	@DisplayName("예상하지 못한 예외는 내부 오류로 덮고 원인을 밖으로 내보내지 않는다")
	void chatHidesUnexpectedCause() {
		Fixture fixture = new Fixture();
		when(fixture.orchestrator.answer(anyLong(), anyString(), any()))
				.thenThrow(new IllegalStateException("커넥션 실패 jdbc:postgresql://db/secret"));

		ChatResponse response = fixture.service.chat(1L, question());

		assertThat(response.errorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR.name());
		assertThat(response.answer())
				.as("내부 사정은 인증된 사용자에게도 노출하지 않는다")
				.doesNotContain("jdbc", "secret");
	}

	@Test
	@DisplayName("대화 행이 만들어진 뒤 실패하면 conversationId 를 응답에 남긴다")
	void chatKeepsConversationIdFromChatTurnException() {
		Fixture fixture = new Fixture();
		when(fixture.orchestrator.answer(anyLong(), anyString(), any()))
				.thenThrow(new ChatTurnException(77L, new BusinessException(ErrorCode.MODEL_UNAVAILABLE)));

		ChatResponse response = fixture.service.chat(1L, question());

		assertThat(response.conversationId()).isEqualTo(77L);
		assertThat(response.errorCode()).isEqualTo(ErrorCode.MODEL_UNAVAILABLE.name());
	}

	@Test
	@DisplayName("자리가 없으면 조합을 시작하지도 않는다")
	void chatRejectsWithoutCallingOrchestrator() {
		Fixture fixture = new Fixture();
		fixture.holdTheOnlySlot();

		ChatResponse response = fixture.service.chat(1L, question());

		assertThat(response.errorCode()).isEqualTo(ErrorCode.MODEL_BUSY.name());
		assertThat(response.retryable()).as("잠시 뒤 다시 하면 되는 거절이다").isTrue();
		verify(fixture.orchestrator, never()).answer(anyLong(), anyString(), any());
	}

	@Test
	@DisplayName("게이트웨이가 넘긴 식별자를 그대로 응답에 싣는다")
	void reusesRequestIdFromMdc() {
		Fixture fixture = new Fixture();
		fixture.answersWith(ChatResponse.completed("무시됨", null, "전체", "답변", List.of()));
		MDC.put(RequestId.MDC_KEY, "gateway-abc123");

		fixture.service.chat(1L, question());

		ArgumentCaptor<String> used = ArgumentCaptor.forClass(String.class);
		verify(fixture.orchestrator).answer(anyLong(), used.capture(), any());
		assertThat(used.getValue()).isEqualTo("gateway-abc123");
	}

	@Test
	@DisplayName("HTTP 가 아닌 경로는 메시지마다 새 식별자를 만든다")
	void generatesPerMessageWhenNoIncomingId() {
		Fixture fixture = new Fixture();
		fixture.narrateWith(sink -> sink.done(ChatStatus.COMPLETED));

		List<String> seen = new ArrayList<>();
		fixture.service.chatInto(1L, question(), requestId -> { seen.add(requestId); return new RecordingChannel(); });
		fixture.service.chatInto(1L, question(), requestId -> { seen.add(requestId); return new RecordingChannel(); });

		assertThat(seen).containsExactly("req-1", "req-2");
	}

	@Test
	@DisplayName("바깥 필터가 심어둔 MDC 를 지우지 않는다")
	void doesNotWipeOuterMdc() {
		Fixture fixture = new Fixture();
		fixture.answersWith(ChatResponse.completed("req", null, "전체", "답변", List.of()));
		MDC.put(RequestId.MDC_KEY, "gateway-abc123");

		fixture.service.chat(1L, question());

		assertThat(MDC.get(RequestId.MDC_KEY)).isEqualTo("gateway-abc123");
	}

	@Test
	@DisplayName("요청 식별자를 MDC 에 남기지 않고 끝낸다")
	void chatDoesNotLeakMdc() {
		Fixture fixture = new Fixture();
		when(fixture.orchestrator.answer(anyLong(), anyString(), any()))
				.thenThrow(new IllegalStateException("boom"));

		fixture.service.chat(1L, question());

		assertThat(MDC.get(MDC_REQUEST_ID)).isNull();
	}

	@Test
	@DisplayName("조합 결과를 통로로 흘리고 complete 로 끝낸다")
	void chatIntoNarratesAndCompletes() {
		Fixture fixture = new Fixture();
		fixture.narrateWith(sink -> {
			sink.meta(5L, "전체", List.of(), "exaone");
			sink.delta("답");
			sink.done(ChatStatus.COMPLETED);
		});

		RecordingChannel channel = fixture.askInto();

		assertThat(channel.calls).containsExactly("meta", "delta", "done", "complete");
		assertThat(channel.errorCode).isNull();
	}

	@Test
	@DisplayName("클라이언트가 끊기면 오류가 아니라 정리로 끝낸다")
	void chatIntoTreatsDisconnectAsCompletion() {
		Fixture fixture = new Fixture();
		fixture.narrateWith(sink -> {
			throw new UncheckedIOException(new IOException("broken pipe"));
		});

		RecordingChannel channel = fixture.askInto();

		assertThat(channel.calls).containsExactly("complete");
		assertThat(channel.errorCode).isNull();
	}

	@Test
	@DisplayName("대화 행이 생긴 뒤 끊겨도 오류가 아니라 정리로 끝낸다")
	void chatIntoTreatsWrappedDisconnectAsCompletion() {
		Fixture fixture = new Fixture();
		fixture.narrateWith(sink -> {
			throw new ChatTurnException(12L, new UncheckedIOException(new IOException("broken pipe")));
		});

		RecordingChannel channel = fixture.askInto();

		assertThat(channel.calls).containsExactly("complete");
		assertThat(channel.errorCode).isNull();
	}

	@Test
	@DisplayName("어떻게 끝나든 다음 질문을 받을 자리가 남는다")
	void chatIntoAlwaysReleasesSlot() {
		Fixture fixture = new Fixture();

		fixture.narrateWith(sink -> {
			throw new UncheckedIOException(new IOException("broken pipe"));
		});
		fixture.askInto();

		fixture.narrateWith(sink -> {
			throw new IllegalStateException("boom");
		});
		fixture.askInto();

		fixture.narrateWith(sink -> {
			throw new BusinessException(ErrorCode.MODEL_UNAVAILABLE);
		});
		fixture.askInto();

		fixture.narrateWith(sink -> {
			throw new ChatTurnException(9L, new BusinessException(ErrorCode.MODEL_UNAVAILABLE));
		});
		fixture.askInto();

		fixture.narrateWith(sink -> sink.done(ChatStatus.COMPLETED));
		RecordingChannel channel = fixture.askInto();
		assertThat(channel.errorCode).isNull();
		assertThat(channel.calls).contains("done");
	}

	@Test
	@DisplayName("대화 행이 생긴 뒤 실패하면 conversationId 를 통로로도 알린다")
	void chatIntoKeepsConversationIdOnFailure() {
		Fixture fixture = new Fixture();
		fixture.narrateWith(sink -> {
			throw new ChatTurnException(42L, new BusinessException(ErrorCode.MODEL_UNAVAILABLE));
		});

		RecordingChannel channel = fixture.askInto();

		assertThat(channel.errorCode).isEqualTo(ErrorCode.MODEL_UNAVAILABLE);
		assertThat(channel.errorConversationId).isEqualTo(42L);
	}

	@Test
	@DisplayName("자리가 없으면 error 프레임만 보내고 소켓을 살려둔다")
	void chatIntoRejectsWithFrameNotException() {
		Fixture fixture = new Fixture();
		fixture.holdTheOnlySlot();

		RecordingChannel channel = new RecordingChannel();
		assertThatCode(() -> fixture.service.chatInto(1L, question(), requestId -> channel))
				.as("여기서 던지면 WebSocket 핸들러가 소켓을 닫게 된다")
				.doesNotThrowAnyException();

		assertThat(channel.errorCode).isEqualTo(ErrorCode.MODEL_BUSY);
		assertThat(channel.calls).containsExactly("error");
		verify(fixture.orchestrator, never()).narrate(anyLong(), anyString(), any(), any());
	}

	@Test
	@DisplayName("조합이 어떻게 실패해도 예외를 밖으로 던지지 않는다")
	void chatIntoNeverThrows() {
		Fixture fixture = new Fixture();
		fixture.narrateWith(sink -> {
			throw new IllegalStateException("boom");
		});

		RecordingChannel channel = new RecordingChannel();
		assertThatCode(() -> fixture.service.chatInto(1L, question(), requestId -> channel))
				.doesNotThrowAnyException();
		assertThat(channel.errorCode).isEqualTo(ErrorCode.INTERNAL_ERROR);
	}

	@Test
	@DisplayName("통로는 서비스가 발급한 요청 식별자로 만들어진다")
	void chatIntoBuildsChannelWithIssuedRequestId() {
		Fixture fixture = new Fixture();
		fixture.narrateWith(sink -> sink.done(ChatStatus.COMPLETED));

		List<String> seen = new ArrayList<>();
		fixture.service.chatInto(1L, question(), requestId -> {
			seen.add(requestId);
			return new RecordingChannel();
		});

		assertThat(seen).containsExactly("req-1");
	}

	@Test
	@DisplayName("자리가 없으면 스트림을 열지 않고 예외로 거절한다")
	void chatStreamThrowsBeforeOpeningStream() {
		Fixture fixture = new Fixture();
		fixture.holdTheOnlySlot();

		assertThatThrownBy(() -> fixture.service.chatStream(1L, question()))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_BUSY));
	}

	@Test
	@DisplayName("작업을 넘기지 못하면 자리를 도로 반납한다")
	void chatStreamReleasesSlotWhenExecutorRejects() {
		Fixture fixture = new Fixture(task -> {
			throw new IllegalStateException("executor rejected");
		});

		assertThatThrownBy(() -> fixture.service.chatStream(1L, question()))
				.isInstanceOf(IllegalStateException.class);

		fixture.answersWith(ChatResponse.completed("req", null, "전체", "답변", List.of()));
		assertThat(fixture.service.chat(1L, question()).status())
				.isEqualTo(ChatStatus.COMPLETED);
	}

	private static ChatRequest question() {
		return new ChatRequest(null, "Nginx SSE 버퍼링 어떻게 해결했지?", null);
	}

	private static final class RecordingChannel implements AnswerChannel {
		final List<String> calls = new ArrayList<>();
		ErrorCode errorCode;
		Long errorConversationId;

		@Override
		public boolean incremental() {
			return true;
		}

		@Override
		public void meta(Long conversationId, String scope, List<Source> sources, String model) {
			calls.add("meta");
		}

		@Override
		public void delta(String text) {
			calls.add("delta");
		}

		@Override
		public void done(ChatStatus reason) {
			calls.add("done");
		}

		@Override
		public void complete() {
			calls.add("complete");
		}

		@Override
		public void error(ErrorCode code, Long conversationId) {
			calls.add("error");
			this.errorCode = code;
			this.errorConversationId = conversationId;
		}
	}

	private static final class Fixture {
		final ChatOrchestrator orchestrator = mock(ChatOrchestrator.class);
		final StreamService streamService = mock(StreamService.class);
		final StreamService.StreamSession session = mock(StreamService.StreamSession.class);
		final ChatService service;

		private final AtomicInteger issued = new AtomicInteger();

		Fixture() {
			this(task -> {
			});
		}

		Fixture(AsyncTaskExecutor executor) {
			this.service = new ChatService(
					orchestrator,
					streamService,
					new ModelWorkloadGate(new ModelConcurrencyProperties(1, Duration.ofSeconds(30))),
					() -> "req-" + issued.incrementAndGet(),
					executor);
			when(streamService.open(anyString())).thenReturn(session);
		}

		void answersWith(ChatResponse response) {
			when(orchestrator.answer(anyLong(), anyString(), any())).thenReturn(response);
		}

		void narrateWith(Consumer<AnswerSink> behavior) {
			doAnswer(invocation -> {
				behavior.accept(invocation.getArgument(3));
				return null;
			}).when(orchestrator).narrate(anyLong(), anyString(), any(), any());
		}

		RecordingChannel askInto() {
			RecordingChannel channel = new RecordingChannel();
			service.chatInto(1L, question(), requestId -> channel);
			return channel;
		}

		void holdTheOnlySlot() {
			service.chatStream(999L, question());
			verifyNoInteractions(orchestrator);
		}
	}
}
