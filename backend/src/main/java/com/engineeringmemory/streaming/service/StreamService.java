package com.engineeringmemory.streaming.service;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.engineeringmemory.chat.enums.ChatStatus;
import com.engineeringmemory.common.exception.ErrorCode;
import com.engineeringmemory.streaming.config.StreamProperties;
import com.engineeringmemory.streaming.dto.StreamEvent;
import com.engineeringmemory.streaming.support.StreamEmitterFactory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class StreamService {

	private final StreamEmitterFactory emitterFactory;
	private final StreamProperties properties;

	public StreamSession open(String requestId) {
		SseEmitter emitter = emitterFactory.create(properties.timeout());

		emitter.onTimeout(() -> {
			log.warn("스트림 타임아웃: requestId={}, timeout={}", requestId, properties.timeout());
			emitter.complete();
		});
		emitter.onError(e -> log.warn("스트림 전송 오류: requestId={}, reason={}", requestId, e.toString()));

		return new StreamSession(emitter);
	}

	public static final class StreamSession {

		private final SseEmitter emitter;

		private StreamSession(SseEmitter emitter) {
			this.emitter = emitter;
		}

		public SseEmitter emitter() {
			return emitter;
		}

		public void meta(StreamEvent.Meta meta) {
			send(StreamEvent.META, meta);
		}

		public void delta(String text) {
			send(StreamEvent.DELTA, new StreamEvent.Delta(text));
		}

		public void done(ChatStatus reason) {
			send(StreamEvent.DONE, new StreamEvent.Done(reason));
		}

		public void error(StreamEvent.Error error) {
			try {
				emitter.send(SseEmitter.event()
						.name(StreamEvent.ERROR)
						.data(error));
			} catch (IOException | IllegalStateException | UncheckedIOException e) {
				log.debug("오류 이벤트 전송 실패(연결 종료 추정): {}", e.toString());
			}
			complete();
		}

		public void complete() {
			emitter.complete();
		}

		private void send(String eventName, Object payload) {
			try {
				emitter.send(SseEmitter.event().name(eventName).data(payload));
			} catch (IOException | IllegalStateException e) {
				throw new UncheckedIOException(new IOException("스트림 전송 실패: event=" + eventName, e));
			}
		}
	}
}
