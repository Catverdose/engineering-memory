package com.engineeringmemory.websocket.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.engineeringmemory.auth.entity.UserAccount;
import com.engineeringmemory.auth.service.AssistantPrincipal;
import com.engineeringmemory.chat.dto.request.ChatRequest;
import com.engineeringmemory.chat.service.ChatService;
import com.engineeringmemory.common.exception.ErrorCode;
import com.engineeringmemory.traffic.limiter.ChatRateLimiter;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import tools.jackson.databind.ObjectMapper;

class ChatWebSocketHandlerTest {

	private static final long OWNER_ID = 73L;

	@Test
	@DisplayName("인증 주체가 없으면 연결을 닫고 등록하지 않는다")
	void rejectsUnauthenticatedConnection() throws Exception {
		Fixture fixture = new Fixture();
		WebSocketSession session = fixture.session();
		when(session.getPrincipal()).thenReturn(null);

		fixture.handler.afterConnectionEstablished(session);

		verify(session).close(CloseStatus.POLICY_VIOLATION);

		fixture.send(session, question());
		verifyNoInteractions(fixture.chatService);
	}

	@Test
	@DisplayName("소유자는 인증 주체에서만 나온다")
	void ownerIdComesFromAuthenticationOnly() throws Exception {
		Fixture fixture = new Fixture();
		WebSocketSession session = fixture.connect();

		fixture.send(session, question());
		fixture.executor.drain();

		verify(fixture.chatService).chatInto(eq(OWNER_ID), any(ChatRequest.class), any());
	}

	@Test
	@DisplayName("본문에 소유자를 끼워 넣어도 인증된 소유자로만 조회한다")
	void bodyCannotOverrideOwner() throws Exception {
		Fixture fixture = new Fixture();
		WebSocketSession session = fixture.connect();

		fixture.send(session, "{\"message\":\"질문\",\"ownerId\":999,\"owner_id\":999}");
		fixture.executor.drain();

		verify(fixture.chatService).chatInto(eq(OWNER_ID), any(ChatRequest.class), any());
		verify(fixture.chatService, never()).chatInto(eq(999L), any(), any());
	}

	@Test
	@DisplayName("앞의 답변이 끝나기 전에 또 물으면 거절한다")
	void rejectsSecondQuestionWhileGenerating() throws Exception {
		Fixture fixture = new Fixture();
		WebSocketSession session = fixture.connect();

		fixture.send(session, question());
		fixture.send(session, question());

		assertThat(fixture.errorCodes()).containsExactly(ErrorCode.TOO_MANY_REQUESTS.name());
		verify(fixture.chatService, never()).chatInto(anyLong(), any(), any());

		fixture.executor.drain();
		verify(fixture.chatService, times(1)).chatInto(anyLong(), any(), any());
	}

	@Test
	@DisplayName("답변이 끝나면 다시 질문을 받는다")
	void acceptsNextQuestionAfterGenerationFinishes() throws Exception {
		Fixture fixture = new Fixture();
		WebSocketSession session = fixture.connect();

		fixture.send(session, question());
		fixture.executor.drain();
		fixture.send(session, question());
		fixture.executor.drain();

		assertThat(fixture.errorCodes()).isEmpty();
		verify(fixture.chatService, times(2)).chatInto(anyLong(), any(), any());
	}

	@Test
	@DisplayName("조합이 터져도 다음 질문을 받는다")
	void releasesGeneratingFlagWhenGenerationThrows() throws Exception {
		Fixture fixture = new Fixture();
		WebSocketSession session = fixture.connect();
		org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
				.when(fixture.chatService).chatInto(anyLong(), any(), any());

		fixture.send(session, question());
		fixture.executor.drain();

		fixture.send(session, question());
		fixture.executor.drain();

		verify(fixture.chatService, times(2)).chatInto(anyLong(), any(), any());
	}

	@Test
	@DisplayName("작업을 넘기지 못해도 다음 질문을 받는다")
	void releasesGeneratingFlagWhenExecutorRejects() throws Exception {
		Fixture fixture = new Fixture();
		WebSocketSession session = fixture.connect();
		fixture.executor.reject = true;

		fixture.send(session, question());
		assertThat(fixture.errorCodes()).containsExactly(ErrorCode.INTERNAL_ERROR.name());

		fixture.executor.reject = false;
		fixture.send(session, question());
		fixture.executor.drain();

		verify(fixture.chatService, times(1)).chatInto(anyLong(), any(), any());
	}

	@Test
	@DisplayName("요청 제한에 걸리면 조합을 시작하지 않는다")
	void rejectsWhenRateLimited() throws Exception {
		Fixture fixture = new Fixture();
		WebSocketSession session = fixture.connect();
		when(fixture.rateLimiter.tryAcquire(anyString())).thenReturn(false);

		fixture.send(session, question());

		assertThat(fixture.errorCodes()).containsExactly(ErrorCode.TOO_MANY_REQUESTS.name());
		verifyNoInteractions(fixture.chatService);
	}

	@Test
	@DisplayName("요청 제한은 HTTP 필터와 같은 기준(원격 IP)으로 센다")
	void countsRateLimitByRemoteAddress() throws Exception {
		Fixture fixture = new Fixture();
		WebSocketSession session = fixture.connect();

		fixture.send(session, question());

		verify(fixture.rateLimiter).tryAcquire("10.0.0.5");
	}

	@Test
	@DisplayName("JSON 이 아니면 거절하고 소켓은 살려둔다")
	void rejectsMalformedJson() throws Exception {
		Fixture fixture = new Fixture();
		WebSocketSession session = fixture.connect();

		fixture.send(session, "이건 JSON 이 아니다");

		assertThat(fixture.errorCodes()).containsExactly(ErrorCode.INVALID_REQUEST.name());
		verifyNoInteractions(fixture.chatService);
		verify(session, never()).close(any());
	}

	@Test
	@DisplayName("컨트롤러와 같은 제약을 쓴다 — 빈 질문과 1,000자 초과를 막는다")
	void appliesSameValidationAsController() throws Exception {
		Fixture fixture = new Fixture();
		WebSocketSession session = fixture.connect();

		fixture.send(session, "{\"message\":\"   \"}");
		fixture.send(session, "{\"message\":\"" + "가".repeat(1001) + "\"}");

		assertThat(fixture.errorCodes())
				.containsExactly(ErrorCode.INVALID_REQUEST.name(), ErrorCode.INVALID_REQUEST.name());
		verifyNoInteractions(fixture.chatService);
	}

	@Test
	@DisplayName("거절이 이어져도 연결을 끊지 않는다")
	void keepsSocketOpenAcrossRejections() throws Exception {
		Fixture fixture = new Fixture();
		WebSocketSession session = fixture.connect();

		fixture.send(session, "깨진 본문");
		fixture.send(session, "{\"message\":\"\"}");
		fixture.send(session, question());
		fixture.executor.drain();

		verify(session, never()).close(any());
		verify(fixture.chatService).chatInto(anyLong(), any(), any());
	}

	@Test
	@DisplayName("닫히는 중에 들어온 메시지는 조용히 버린다")
	void ignoresMessageAfterConnectionClosed() throws Exception {
		Fixture fixture = new Fixture();
		WebSocketSession session = fixture.connect();
		fixture.handler.afterConnectionClosed(session, CloseStatus.NORMAL);

		assertThatCode(() -> fixture.send(session, question())).doesNotThrowAnyException();
		verifyNoInteractions(fixture.chatService);
	}

	private static String question() {
		return "{\"message\":\"Nginx SSE 버퍼링 어떻게 해결했지?\"}";
	}

	private static final class ControllableExecutor implements AsyncTaskExecutor {
		private final List<Runnable> pending = new ArrayList<>();
		boolean reject;

		@Override
		public void execute(Runnable task) {
			if (reject) {
				throw new IllegalStateException("executor rejected");
			}
			pending.add(task);
		}

		void drain() {
			List<Runnable> tasks = List.copyOf(pending);
			pending.clear();
			tasks.forEach(Runnable::run);
		}
	}

	private static final class Fixture {
		final ChatService chatService = mock(ChatService.class);
		final ChatRateLimiter rateLimiter = mock(ChatRateLimiter.class);
		final ControllableExecutor executor = new ControllableExecutor();
		final ObjectMapper objectMapper = new ObjectMapper();
		final ChatWebSocketHandler handler;

		Fixture() {
			ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
			Validator validator = factory.getValidator();
			when(rateLimiter.tryAcquire(anyString())).thenReturn(true);
			this.handler = new ChatWebSocketHandler(
					chatService, rateLimiter, validator, objectMapper, executor);
		}

		WebSocketSession session() {
			WebSocketSession session = mock(WebSocketSession.class);
			when(session.getId()).thenReturn("s1");
			when(session.isOpen()).thenReturn(true);
			when(session.getRemoteAddress())
					.thenReturn(new InetSocketAddress("10.0.0.5", 51000));
			return session;
		}

		WebSocketSession connect() throws Exception {
			WebSocketSession session = session();
			AssistantPrincipal principal = principal();
			when(session.getPrincipal()).thenReturn(
					UsernamePasswordAuthenticationToken.authenticated(
							principal, null, principal.getAuthorities()));
			handler.afterConnectionEstablished(session);
			this.connected = session;
			return session;
		}

		void send(WebSocketSession session, String payload) {
			handler.handleTextMessage(session, new TextMessage(payload));
		}

		private WebSocketSession connected;

		List<String> errorCodes() {
			return sentFrames().stream()
					.filter(frame -> "error".equals(frame.get("type")))
					.map(frame -> String.valueOf(dataOf(frame).get("code")))
					.toList();
		}

		@SuppressWarnings("unchecked")
		private Map<String, Object> dataOf(Map<String, Object> frame) {
			return (Map<String, Object>) frame.get("data");
		}

		@SuppressWarnings("unchecked")
		private List<Map<String, Object>> sentFrames() {
			ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
			try {
				verify(connected, atLeast(0)).sendMessage(captor.capture());
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
			return captor.getAllValues().stream()
					.map(TextMessage::getPayload)
					.map(payload -> (Map<String, Object>) objectMapper.readValue(payload, Map.class))
					.toList();
		}

		private static AssistantPrincipal principal() throws Exception {
			UserAccount account = UserAccount.initialOwner("owner", "{noop}secret");
			Field id = UserAccount.class.getDeclaredField("id");
			id.setAccessible(true);
			id.set(account, OWNER_ID);
			return AssistantPrincipal.from(account);
		}
	}
}
