package com.engineeringmemory.application.chat;

import java.util.List;

import com.engineeringmemory.chat.dto.response.ChatResponse.Source;
import com.engineeringmemory.chat.enums.ChatStatus;

public interface AnswerSink {

	boolean incremental();

	void meta(Long conversationId, String scope, List<Source> sources, String model);

	void delta(String text);

	void done(ChatStatus reason);
}
