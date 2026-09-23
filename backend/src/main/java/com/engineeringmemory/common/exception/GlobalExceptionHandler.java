package com.engineeringmemory.common.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
		ErrorCode errorCode = e.getErrorCode();

		if (errorCode == ErrorCode.MODEL_BUSY) {
			log.debug("BusinessException: code={}", errorCode);
		} else if (errorCode.getStatus().is5xxServerError()) {
			log.error("BusinessException: code={}", errorCode, e);
		} else {
			log.warn("BusinessException: code={}, message={}", errorCode, e.getMessage());
		}

		return ResponseEntity.status(errorCode.getStatus())
				.body(ErrorResponse.of(errorCode, e.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException e) {
		String message = e.getBindingResult().getFieldErrors().stream()
				.map(GlobalExceptionHandler::formatFieldError)
				.reduce((a, b) -> a + ", " + b)
				.orElse(ErrorCode.INVALID_REQUEST.getDefaultMessage());

		log.warn("요청 본문 검증 실패: {}", message);
		return badRequest(message);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException e) {
		log.warn("요청 본문을 읽을 수 없음: {}", e.getMostSpecificCause().getMessage());
		return badRequest("요청 본문을 읽을 수 없습니다. JSON 형식과 UTF-8 인코딩을 확인해 주세요.");
	}

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException e) {
		log.warn("요청 파라미터 검증 실패: {}", e.getMessage());
		return badRequest(e.getMessage());
	}

	@ExceptionHandler(HandlerMethodValidationException.class)
	public ResponseEntity<ErrorResponse> handleMethodValidation(HandlerMethodValidationException e) {
		log.warn("요청 파라미터 검증 실패: {}", e.getMessage());
		return badRequest("요청 파라미터의 개수나 길이가 허용 범위를 벗어났습니다.");
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
		log.warn("요청 파라미터 타입 불일치: name={}", e.getName());
		return badRequest(e.getName() + " 값의 형식이 올바르지 않습니다.");
	}

	@ExceptionHandler({ MissingServletRequestParameterException.class, MissingServletRequestPartException.class })
	public ResponseEntity<ErrorResponse> handleMissingRequestValue(Exception e) {
		log.warn("필수 요청 값 누락: {}", e.getMessage());
		return badRequest("필수 요청 값이 누락되었습니다.");
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException e) {
		log.warn("지원하지 않는 Content-Type: {}", e.getContentType());
		return ResponseEntity.status(ErrorCode.UNSUPPORTED_FILE_TYPE.getStatus())
				.body(ErrorResponse.of(ErrorCode.UNSUPPORTED_FILE_TYPE));
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<ErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException e) {
		log.warn("업로드 크기 제한 초과: {}", e.getMessage());
		return ResponseEntity.status(ErrorCode.FILE_TOO_LARGE.getStatus())
				.body(ErrorResponse.of(ErrorCode.FILE_TOO_LARGE));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
		log.warn("잘못된 인자: {}", e.getMessage());
		return badRequest(e.getMessage());
	}

	@ExceptionHandler(AsyncRequestNotUsableException.class)
	public void handleDisconnectedClient(AsyncRequestNotUsableException e) {
		log.debug("클라이언트 연결 종료로 응답을 쓸 수 없음: {}", e.getMessage());
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception e) {
		log.error("처리되지 않은 예외", e);
		return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getStatus())
				.body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR));
	}

	private static ResponseEntity<ErrorResponse> badRequest(String message) {
		return ResponseEntity.status(ErrorCode.INVALID_REQUEST.getStatus())
				.body(ErrorResponse.of(ErrorCode.INVALID_REQUEST, message));
	}

	private static String formatFieldError(FieldError fieldError) {
		return fieldError.getField() + ": " + fieldError.getDefaultMessage();
	}
}
