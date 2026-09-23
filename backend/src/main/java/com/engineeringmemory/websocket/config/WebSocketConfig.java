package com.engineeringmemory.websocket.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import com.engineeringmemory.websocket.handler.ChatWebSocketHandler;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

	private final WebSocketProperties properties;
	private final ChatWebSocketHandler chatWebSocketHandler;

	public WebSocketConfig(WebSocketProperties properties, ChatWebSocketHandler chatWebSocketHandler) {
		this.properties = properties;
		this.chatWebSocketHandler = chatWebSocketHandler;
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		if (!properties.enabled()) {
			log.info("채팅 WebSocket 을 등록하지 않습니다(chat.websocket.enabled=false). SSE 경로는 그대로 동작합니다.");
			return;
		}

		WebSocketHandlerRegistration registration =
				registry.addHandler(chatWebSocketHandler, properties.path());

		if (properties.allowedOrigins().isEmpty()) {
			log.info("채팅 WebSocket: path={}, 동일 출처만 허용", properties.path());
		} else {
			registration.setAllowedOrigins(properties.allowedOrigins().toArray(String[]::new));
			log.info("채팅 WebSocket: path={}, 허용 출처={}", properties.path(), properties.allowedOrigins());
		}
	}

	@Bean
	ServletServerContainerFactoryBean createWebSocketContainer() {
		ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
		container.setMaxTextMessageBufferSize(properties.maxMessageBytes());
		container.setMaxBinaryMessageBufferSize(properties.maxMessageBytes());
		container.setMaxSessionIdleTimeout(properties.idleTimeout().toMillis());
		return container;
	}
}
