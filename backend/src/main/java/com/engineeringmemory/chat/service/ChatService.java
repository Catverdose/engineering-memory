package com.engineeringmemory.chat.service;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.Function;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.engineeringmemory.application.chat.ChatOrchestrator;
import com.engineeringmemory.application.chat.ChatTurnException;
import com.engineeringmemory.chat.dto.request.ChatRequest;
import com.engineeringmemory.chat.dto.response.ChatResponse;
import com.engineeringmemory.chat.dto.response.ChatResponse.Source;
import com.engineeringmemory.chat.enums.ChatStatus;
import com.engineeringmemory.common.exception.BusinessException;
import com.engineeringmemory.common.exception.ErrorCode;
import com.engineeringmemory.common.support.RequestId;
import com.engineeringmemory.common.support.RequestIdGenerator;
import com.engineeringmemory.streaming.dto.StreamEvent;
import com.engineeringmemory.streaming.service.StreamService;
import com.engineeringmemory.llm.workload.ModelWorkloadGate;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ChatService {

	private static final String MDC_REQUEST_ID = RequestId.MDC_KEY;

	private final ChatOrchestrator orchestrator;
	private final StreamService streamService;
	private final RequestIdGenerator requestIdGenerator;
	private final AsyncTaskExecutor taskExecutor;
	private final ModelWorkloadGate modelGate;

	public ChatService(
			ChatOrchestrator orchestrator,
			StreamService streamService,
			ModelWorkloadGate modelGate,
			RequestIdGenerator requestIdGenerator,
			@Qualifier("applicationTaskExecutor") AsyncTaskExecutor taskExecutor) {
		this.orchestrator = orchestrator;
		this.streamService = streamService;
		this.requestIdGenerator = requestIdGenerator;
		this.taskExecutor = taskExecutor;
		this.modelGate = modelGate;
	}

	public ChatResponse chat(long ownerId, ChatRequest request) {
		String requestId = newRequestId();
		String previous = MDC.get(MDC_REQUEST_ID);
		MDC.put(MDC_REQUEST_ID, requestId);
		if (!modelGate.tryAcquireInteractive()) {
			logModelBusy(requestId, ownerId);
			restoreMdc(previous);
			return ChatResponse.failed(requestId, ErrorCode.MODEL_BUSY);
		}

		try {
			return orchestrator.answer(ownerId, requestId, request);
		} catch (ChatTurnException e) {
			ErrorCode errorCode = errorCodeOf(e.getCause());
			logFailure(ownerId, errorCode, e.getCause());
			return ChatResponse.failed(requestId, e.conversationId(), errorCode);
		} catch (BusinessException e) {
			log.warn("채팅 처리 실패: ownerId={}, errorCode={}", ownerId, e.getErrorCode());
			return ChatResponse.failed(requestId, e.getErrorCode());
		} catch (RuntimeException e) {
			log.error("채팅 처리 중 예상하지 못한 오류: ownerId={}", ownerId, e);
			return ChatResponse.failed(requestId, ErrorCode.INTERNAL_ERROR);
		} finally {
			modelGate.releaseInteractive();
			restoreMdc(previous);
		}
	}

	public SseEmitter chatStream(long ownerId, ChatRequest request) {
		String requestId = newRequestId();
		if (!modelGate.tryAcquireInteractive()) {
			logModelBusy(requestId, ownerId);
			throw new BusinessException(ErrorCode.MODEL_BUSY);
		}

		try {
			StreamService.StreamSession session = streamService.open(requestId);
			AnswerChannel channel = new StreamingAnswerChannel(requestId, session);
			taskExecutor.execute(() -> run(ownerId, requestId, request, channel, true));
			return session.emitter();
		} catch (RuntimeException e) {
			modelGate.releaseInteractive();
			throw e;
		}
	}

	public void chatInto(long ownerId, ChatRequest request,
			Function<String, AnswerChannel> channelFactory) {
		String requestId = newRequestId();
		AnswerChannel channel = channelFactory.apply(requestId);
		if (!modelGate.tryAcquireInteractive()) {
			logModelBusy(requestId, ownerId);
			channel.error(ErrorCode.MODEL_BUSY, null);
			return;
		}
		run(ownerId, requestId, request, channel, true);
	}

	private void run(long ownerId, String requestId, ChatRequest request,
			AnswerChannel channel, boolean holdsModelSlot) {
		String previous = MDC.get(MDC_REQUEST_ID);
		MDC.put(MDC_REQUEST_ID, requestId);
		try {
			orchestrator.narrate(ownerId, requestId, request, channel);
			channel.complete();
		} catch (ChatTurnException e) {
			if (e.getCause() instanceof UncheckedIOException) {
				log.info("클라이언트 연결 종료로 답변 중단: ownerId={}, requestId={}", ownerId, requestId);
				channel.complete();
			} else {
				ErrorCode errorCode = errorCodeOf(e.getCause());
				logFailure(ownerId, errorCode, e.getCause());
				channel.error(errorCode, e.conversationId());
			}
		} catch (BusinessException e) {
			log.warn("채팅 조합 실패: ownerId={}, errorCode={}", ownerId, e.getErrorCode());
			channel.error(e.getErrorCode(), null);
		} catch (UncheckedIOException e) {
			log.info("클라이언트 연결 종료로 답변 중단: ownerId={}, requestId={}", ownerId, requestId);
			channel.complete();
		} catch (RuntimeException e) {
			log.error("채팅 조합 중 예상하지 못한 오류: ownerId={}", ownerId, e);
			channel.error(ErrorCode.INTERNAL_ERROR, null);
		} finally {
			if (holdsModelSlot) {
				modelGate.releaseInteractive();
			}
			restoreMdc(previous);
		}
	}

	private String newRequestId() {
		return RequestId.current().orElseGet(requestIdGenerator::newRequestId);
	}

	private static void restoreMdc(String previous) {
		if (previous == null) {
			MDC.remove(MDC_REQUEST_ID);
		} else {
			MDC.put(MDC_REQUEST_ID, previous);
		}
	}

	private static ErrorCode errorCodeOf(Throwable failure) {
		return failure instanceof BusinessException business
				? business.getErrorCode()
				: ErrorCode.INTERNAL_ERROR;
	}

	private static void logFailure(long ownerId, ErrorCode errorCode, Throwable failure) {
		if (failure instanceof BusinessException) {
			log.warn("채팅 처리 실패: ownerId={}, errorCode={}", ownerId, errorCode);
		} else {
			log.error("채팅 처리 중 예상하지 못한 오류: ownerId={}", ownerId, failure);
		}
	}

	private void logModelBusy(String requestId, long ownerId) {
		log.warn("모델 동시 요청 상한 도달: ownerId={}, requestId={}, inFlight={}, max={}",
				ownerId, requestId, modelGate.interactiveInFlight(), modelGate.maxInteractive());
	}

	private record StreamingAnswerChannel(String requestId, StreamService.StreamSession session)
			implements AnswerChannel {

		@Override
		public boolean incremental() {
			return true;
		}

		@Override
		public void meta(Long conversationId, String scope, List<Source> sources, String model) {
			session.meta(new StreamEvent.Meta(requestId, conversationId, scope, sources, model));
		}

		@Override
		public void delta(String text) {
			session.delta(text);
		}

		@Override
		public void done(ChatStatus reason) {
			session.done(reason);
		}

		@Override
		public void complete() {
			session.complete();
		}

		@Override
		public void error(ErrorCode errorCode, Long conversationId) {
			session.error(new StreamEvent.Error(
					requestId, conversationId, errorCode.name(),
					errorCode.getDefaultMessage(), errorCode.isRetryable()));
		}
	}
}
