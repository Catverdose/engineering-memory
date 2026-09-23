package com.engineeringmemory.application.chat;

import java.util.List;

import com.engineeringmemory.chat.dto.response.ChatResponse;
import com.engineeringmemory.chat.dto.response.ChatResponse.Source;
import com.engineeringmemory.chat.enums.ChatStatus;

final class CollectingAnswerSink implements AnswerSink {

	private final String requestId;
	private final StringBuilder answer = new StringBuilder(1024);
	private Long conversationId;
	private String scope;
	private List<Source> sources = List.of();
	private ChatStatus status;

	CollectingAnswerSink(String requestId) {
		this.requestId = requestId;
	}

	@Override
	public boolean incremental() {
		return false;
	}

	@Override
	public void meta(Long conversationId, String scope, List<Source> sources, String model) {
		this.conversationId = conversationId;
		this.scope = scope;
		this.sources = List.copyOf(sources);
	}

	@Override
	public void delta(String text) {
		answer.append(text);
	}

	@Override
	public void done(ChatStatus reason) {
		this.status = reason;
	}

	ChatResponse toResponse() {
		if (status == null || conversationId == null) {
			throw new IllegalStateException("완료 신호 또는 대화 식별자 없이 응답을 만들 수 없습니다.");
		}
		return switch (status) {
			case COMPLETED -> ChatResponse.completed(requestId, conversationId, scope, answer.toString(), sources);
			case NO_CONTEXT -> ChatResponse.noContext(requestId, conversationId, scope);
			case FAILED -> throw new IllegalStateException("FAILED는 예외 경로에서 직접 만듭니다.");
		};
	}
}
