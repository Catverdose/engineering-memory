package com.engineeringmemory.websocket.support;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.engineeringmemory.websocket.dto.WsFrame;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

@Slf4j
public final class WebSocketSender {

	private final WebSocketSession session;
	private final ObjectMapper objectMapper;

	private final Object sendLock = new Object();

	public WebSocketSender(WebSocketSession session, ObjectMapper objectMapper) {
		this.session = session;
		this.objectMapper = objectMapper;
	}

	public String sessionId() {
		return session.getId();
	}

	public boolean isOpen() {
		return session.isOpen();
	}

	public void send(WsFrame frame) {
		String json = objectMapper.writeValueAsString(frame);

		synchronized (sendLock) {
			if (!session.isOpen()) {
				throw new UncheckedIOException(
						new IOException("WebSocket 이 이미 닫혔습니다: type=" + frame.type()));
			}
			try {
				session.sendMessage(new TextMessage(json));
			} catch (IOException | IllegalStateException e) {
				throw new UncheckedIOException(
						new IOException("WebSocket 전송 실패: type=" + frame.type(), e));
			}
		}
	}

	public void sendQuietly(WsFrame frame) {
		try {
			send(frame);
		} catch (UncheckedIOException e) {
			log.debug("프레임 전송 실패(연결 종료 추정): type={}, reason={}", frame.type(), e.toString());
		}
	}

	public void close(CloseStatus status) {
		try {
			session.close(status);
		} catch (IOException e) {
			log.debug("WebSocket 종료 실패: sessionId={}, reason={}", session.getId(), e.toString());
		}
	}
}
