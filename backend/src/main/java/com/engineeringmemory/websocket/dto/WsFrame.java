package com.engineeringmemory.websocket.dto;

import com.engineeringmemory.chat.enums.ChatStatus;
import com.engineeringmemory.common.exception.ErrorCode;
import com.engineeringmemory.streaming.dto.StreamEvent;

public record WsFrame(String type, Object data) {

	public static WsFrame meta(StreamEvent.Meta meta) {
		return new WsFrame(StreamEvent.META, meta);
	}

	public static WsFrame delta(String text) {
		return new WsFrame(StreamEvent.DELTA, new StreamEvent.Delta(text));
	}

	public static WsFrame done(ChatStatus reason) {
		return new WsFrame(StreamEvent.DONE, new StreamEvent.Done(reason));
	}

	public static WsFrame error(ErrorCode errorCode) {
		return new WsFrame(StreamEvent.ERROR, StreamEvent.Error.from(errorCode, errorCode.getDefaultMessage()));
	}

	public static WsFrame error(String requestId, Long conversationId, ErrorCode errorCode) {
		return new WsFrame(StreamEvent.ERROR, new StreamEvent.Error(
				requestId, conversationId, errorCode.name(),
				errorCode.getDefaultMessage(), errorCode.isRetryable()));
	}
}
