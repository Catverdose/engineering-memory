package com.engineeringmemory.streaming.support;

import java.time.Duration;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface StreamEmitterFactory {

	SseEmitter create(Duration timeout);

	@Component
	class Default implements StreamEmitterFactory {
		@Override
		public SseEmitter create(Duration timeout) {
			return new SseEmitter(timeout.toMillis());
		}
	}
}
