package com.engineeringmemory.chat.dto.response;

import java.time.LocalDate;
import java.util.List;

import com.engineeringmemory.chat.enums.ChatStatus;
import com.engineeringmemory.common.exception.ErrorCode;
import com.engineeringmemory.knowledge.dto.ChunkHit;

public record ChatResponse(
		String requestId,
		Long conversationId,
		ChatStatus status,
		String scope,
		String answer,
		List<Source> sources,
		String errorCode,
		boolean retryable) {

	public static ChatResponse completed(String requestId, Long conversationId, String scope,
			String answer, List<Source> sources) {
		return new ChatResponse(requestId, conversationId, ChatStatus.COMPLETED, scope,
				answer, List.copyOf(sources), null, false);
	}

	public static ChatResponse noContext(String requestId, Long conversationId, String scope) {
		return new ChatResponse(requestId, conversationId, ChatStatus.NO_CONTEXT, scope,
				ErrorCode.NO_CONTEXT.getDefaultMessage(), List.of(),
				ErrorCode.NO_CONTEXT.name(), false);
	}

	public static ChatResponse failed(String requestId, ErrorCode errorCode) {
		return failed(requestId, null, errorCode);
	}

	public static ChatResponse failed(String requestId, Long conversationId, ErrorCode errorCode) {
		return new ChatResponse(requestId, conversationId, ChatStatus.FAILED, null,
				errorCode.getDefaultMessage(), List.of(), errorCode.name(), errorCode.isRetryable());
	}

	public record Source(
			Long documentId,
			Long chunkId,
			String title,
			String documentType,
			String documentTypeLabel,
			List<String> projects,
			List<String> technologies,
			List<String> tags,
			LocalDate occurredOn,
			int chunkIndex,
			String heading,
			double similarity,
			String snippet) {

		private static final int SNIPPET_CHARS = 240;

		public static Source from(ChunkHit hit) {
			return new Source(
					hit.documentId(), hit.chunkId(), hit.title(), hit.documentType().name(),
					hit.documentType().getLabel(), hit.projects(), hit.technologies(), hit.tags(),
					hit.occurredOn(), hit.chunkIndex(), hit.heading(), hit.similarity(),
					hit.snippet(SNIPPET_CHARS));
		}
	}
}
