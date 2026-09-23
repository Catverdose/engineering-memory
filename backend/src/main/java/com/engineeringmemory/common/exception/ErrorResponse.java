package com.engineeringmemory.common.exception;

public record ErrorResponse(String code, String message, boolean retryable) {

	public static ErrorResponse of(ErrorCode errorCode) {
		return new ErrorResponse(errorCode.name(), errorCode.getDefaultMessage(), errorCode.isRetryable());
	}

	public static ErrorResponse of(ErrorCode errorCode, String message) {
		return new ErrorResponse(errorCode.name(), message, errorCode.isRetryable());
	}
}
