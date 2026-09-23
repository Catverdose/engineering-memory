package com.engineeringmemory.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

	INVALID_REQUEST(HttpStatus.BAD_REQUEST, false,
			"요청 값이 올바르지 않습니다."),

	NOT_FOUND(HttpStatus.NOT_FOUND, false,
			"요청한 데이터를 찾을 수 없습니다."),

	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, false,
			"로그인이 필요합니다."),

	FORBIDDEN(HttpStatus.FORBIDDEN, false,
			"이 작업을 수행할 권한이 없습니다."),

	INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, false,
			"아이디 또는 비밀번호가 올바르지 않습니다."),

	NO_CONTEXT(HttpStatus.OK, false,
			"내 지식베이스에서 이 질문의 근거를 찾지 못했습니다. 검색 범위를 확인하거나 관련 자료를 먼저 등록해 주세요."),

	DUPLICATE_DOCUMENT(HttpStatus.CONFLICT, false,
			"같은 내용의 문서가 이미 등록되어 있습니다."),

	CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, true,
			"다른 요청이 데이터를 먼저 변경했습니다. 최신 상태를 확인한 뒤 다시 시도해 주세요."),

	UNSUPPORTED_FILE_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, false,
			"지원하지 않는 파일 형식입니다. txt, md, log, pdf 파일만 등록할 수 있습니다."),

	FILE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, false,
			"파일이 허용된 크기를 초과했습니다."),

	CONFIGURATION_MISSING(HttpStatus.SERVICE_UNAVAILABLE, false,
			"서버 설정이 완료되지 않아 이 기능을 사용할 수 없습니다."),

	MODEL_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, true,
			"AI 모델을 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."),

	MODEL_BUSY(HttpStatus.SERVICE_UNAVAILABLE, true,
			"지금 상담 요청이 많아 답변할 수 없습니다. 잠시 후 다시 시도해 주세요."),

	EXTERNAL_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, true,
			"외부 서비스를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."),

	INDEXING_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, true,
			"검색 데이터 반영에 실패했습니다. 재처리가 필요합니다."),

	INVARIANT_VIOLATION(HttpStatus.INTERNAL_SERVER_ERROR, false,
			"데이터 정합성 오류가 발생했습니다. 관리자에게 문의해 주세요."),

	TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, true,
			"요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),

	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, true,
			"서버 처리 중 오류가 발생했습니다.");

	private final HttpStatus status;
	private final boolean retryable;
	private final String defaultMessage;

	ErrorCode(HttpStatus status, boolean retryable, String defaultMessage) {
		this.status = status;
		this.retryable = retryable;
		this.defaultMessage = defaultMessage;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public boolean isRetryable() {
		return retryable;
	}

	public String getDefaultMessage() {
		return defaultMessage;
	}
}
