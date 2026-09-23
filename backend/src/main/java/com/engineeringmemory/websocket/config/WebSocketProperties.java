package com.engineeringmemory.websocket.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Validated
@ConfigurationProperties(prefix = "chat.websocket")
public record WebSocketProperties(

		@DefaultValue("true") boolean enabled,

		@NotBlank @DefaultValue("/api/chat/ws") String path,

		List<String> allowedOrigins,

		@Positive @Max(65_536) @DefaultValue("8192") int maxMessageBytes,

		@NotNull @DefaultValue("600s") Duration idleTimeout) {

	public WebSocketProperties {
		allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);

		if (allowedOrigins.contains("*")) {
			throw new IllegalArgumentException(
					"chat.websocket.allowed-origins 에 \"*\" 는 쓸 수 없습니다. "
							+ "허용할 출처를 명시하거나, 비워 두어 동일 출처만 받으세요.");
		}
		if (!path.startsWith("/")) {
			throw new IllegalArgumentException("chat.websocket.path 는 / 로 시작해야 합니다: " + path);
		}
	}
}
