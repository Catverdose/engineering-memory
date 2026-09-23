package com.engineeringmemory.application.chat;

public final class ChatTurnException extends RuntimeException {

	private static final long serialVersionUID = 1L;
	private final Long conversationId;

	public ChatTurnException(Long conversationId, RuntimeException cause) {
		super(cause.getMessage(), cause);
		this.conversationId = conversationId;
	}

	public Long conversationId() {
		return conversationId;
	}
}
