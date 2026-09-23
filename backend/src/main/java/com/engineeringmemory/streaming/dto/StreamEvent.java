package com.engineeringmemory.streaming.dto;

import java.util.List;

import com.engineeringmemory.chat.dto.response.ChatResponse.Source;
import com.engineeringmemory.chat.enums.ChatStatus;
import com.engineeringmemory.common.exception.ErrorCode;

public final class StreamEvent {

	public static final String META = "meta";
	public static final String DELTA = "delta";
	public static final String DONE = "done";
	public static final String ERROR = "error";

	private StreamEvent() {
	}

	public record Meta(
			String requestId,
			Long conversationId,
			String scope,
			List<Source> sources,
			String model) {
	}

	public record Delta(String text) {
	}

	public record Done(ChatStatus reason) {
	}

	public record Error(
			String requestId,
			Long conversationId,
			String code,
			String message,
			boolean retryable) {

		public static Error from(ErrorCode errorCode, String message) {
			return new Error(null, null, errorCode.name(), message, errorCode.isRetryable());
		}
	}
}
