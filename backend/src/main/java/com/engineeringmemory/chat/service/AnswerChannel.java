package com.engineeringmemory.chat.service;

import com.engineeringmemory.application.chat.AnswerSink;
import com.engineeringmemory.common.exception.ErrorCode;

public interface AnswerChannel extends AnswerSink {

	void complete();

	void error(ErrorCode errorCode, Long conversationId);
}
