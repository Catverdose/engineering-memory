package com.engineeringmemory.websocket.handler;

import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.engineeringmemory.auth.security.OwnerContext;
import com.engineeringmemory.chat.dto.request.ChatRequest;
import com.engineeringmemory.chat.dto.response.ChatResponse.Source;
import com.engineeringmemory.chat.enums.ChatStatus;
import com.engineeringmemory.chat.service.AnswerChannel;
import com.engineeringmemory.chat.service.ChatService;
import com.engineeringmemory.common.exception.ErrorCode;
import com.engineeringmemory.streaming.dto.StreamEvent;
import com.engineeringmemory.traffic.limiter.ChatRateLimiter;
import com.engineeringmemory.websocket.dto.WsFrame;
import com.engineeringmemory.websocket.support.WebSocketSender;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

	private final ChatService chatService;
	private final ChatRateLimiter rateLimiter;
	private final Validator validator;
	private final ObjectMapper objectMapper;
	private final AsyncTaskExecutor taskExecutor;

	private final Map<String, Connection> connections = new ConcurrentHashMap<>();

	public ChatWebSocketHandler(
			ChatService chatService,
			ChatRateLimiter rateLimiter,
			Validator validator,
			ObjectMapper objectMapper,
			@Qualifier("applicationTaskExecutor") AsyncTaskExecutor taskExecutor) {
		this.chatService = chatService;
		this.rateLimiter = rateLimiter;
		this.validator = validator;
		this.objectMapper = objectMapper;
		this.taskExecutor = taskExecutor;
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) throws Exception {
		Principal principal = session.getPrincipal();
		if (!(principal instanceof Authentication authentication)) {
			log.warn("인증 주체 없는 WebSocket 연결 거부: sessionId={}", session.getId());
			session.close(CloseStatus.POLICY_VIOLATION);
			return;
		}
		long ownerId = OwnerContext.requireOwnerId(authentication);
		connections.put(session.getId(),
				new Connection(new WebSocketSender(session, objectMapper), clientKey(session), ownerId));
		log.debug("WebSocket 연결: sessionId={}, 열린 연결={}", session.getId(), connections.size());
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		connections.remove(session.getId());
		log.debug("WebSocket 종료: sessionId={}, status={}, 열린 연결={}",
				session.getId(), status, connections.size());
	}

	@Override
	public void handleTransportError(WebSocketSession session, Throwable exception) {
		log.debug("WebSocket 전송 오류: sessionId={}, reason={}", session.getId(), exception.toString());
		connections.remove(session.getId());
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) {
		Connection conn = connections.get(session.getId());
		if (conn == null) {
			return;
		}

		try {
			dispatch(conn, message.getPayload());
		} catch (UncheckedIOException e) {
			log.debug("WebSocket 응답 전송 실패: sessionId={}, reason={}", session.getId(), e.toString());
		}
	}

	private void dispatch(Connection conn, String payload) {
		ChatRequest request = parse(conn, payload);
		if (request == null) {
			return;
		}

		if (!rateLimiter.tryAcquire(conn.clientKey())) {
			rateLimiter.logRejected("websocket", conn.clientKey());
			conn.sender().send(WsFrame.error(ErrorCode.TOO_MANY_REQUESTS));
			return;
		}

		if (!conn.generating().compareAndSet(false, true)) {
			log.debug("이전 답변이 진행 중이라 거절: sessionId={}", conn.sender().sessionId());
			conn.sender().send(WsFrame.error(ErrorCode.TOO_MANY_REQUESTS));
			return;
		}

		try {
			taskExecutor.execute(() -> generate(conn, request));
		} catch (RuntimeException e) {
			conn.generating().set(false);
			log.error("WebSocket 답변 작업을 넘기지 못했습니다: sessionId={}", conn.sender().sessionId(), e);
			conn.sender().sendQuietly(WsFrame.error(ErrorCode.INTERNAL_ERROR));
		}
	}

	private void generate(Connection conn, ChatRequest request) {
		try {
			chatService.chatInto(conn.ownerId(), request,
					requestId -> new WebSocketAnswerChannel(requestId, conn.sender()));
		} catch (RuntimeException e) {
			log.error("WebSocket 답변 처리 중 예상하지 못한 오류: sessionId={}", conn.sender().sessionId(), e);
			conn.sender().sendQuietly(WsFrame.error(ErrorCode.INTERNAL_ERROR));
		} finally {
			conn.generating().set(false);
		}
	}

	private ChatRequest parse(Connection conn, String payload) {
		ChatRequest request;
		try {
			request = objectMapper.readValue(payload, ChatRequest.class);
		} catch (RuntimeException e) {
			log.debug("WebSocket 메시지를 읽지 못했습니다: {}", e.toString());
			conn.sender().send(WsFrame.error(ErrorCode.INVALID_REQUEST));
			return null;
		}

		if (request == null) {
			conn.sender().send(WsFrame.error(ErrorCode.INVALID_REQUEST));
			return null;
		}

		Set<ConstraintViolation<ChatRequest>> violations = validator.validate(request);
		if (!violations.isEmpty()) {
			log.debug("WebSocket 메시지 검증 실패: {}", violations.iterator().next().getMessage());
			conn.sender().send(WsFrame.error(ErrorCode.INVALID_REQUEST));
			return null;
		}
		return request;
	}

	private String clientKey(WebSocketSession session) {
		InetSocketAddress remote = session.getRemoteAddress();
		if (remote != null && remote.getAddress() != null) {
			return remote.getAddress().getHostAddress();
		}
		log.warn("WebSocket 원격 주소를 읽지 못해 세션 id 로 대체합니다: sessionId={}", session.getId());
		return session.getId();
	}

	private record Connection(WebSocketSender sender, String clientKey, long ownerId,
			AtomicBoolean generating) {

		Connection(WebSocketSender sender, String clientKey, long ownerId) {
			this(sender, clientKey, ownerId, new AtomicBoolean(false));
		}
	}

	private record WebSocketAnswerChannel(String requestId, WebSocketSender sender)
			implements AnswerChannel {

		@Override
		public boolean incremental() {
			return true;
		}

		@Override
		public void meta(Long conversationId, String scope, List<Source> sources, String model) {
			sender.send(WsFrame.meta(
					new StreamEvent.Meta(requestId, conversationId, scope, sources, model)));
		}

		@Override
		public void delta(String text) {
			sender.send(WsFrame.delta(text));
		}

		@Override
		public void done(ChatStatus reason) {
			sender.send(WsFrame.done(reason));
		}

		@Override
		public void complete() {
		}

		@Override
		public void error(ErrorCode errorCode, Long conversationId) {
			sender.sendQuietly(WsFrame.error(requestId, conversationId, errorCode));
		}
	}
}
