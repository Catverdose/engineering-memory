package com.engineeringmemory.application.chat;

import java.util.List;

import org.springframework.stereotype.Service;

import com.engineeringmemory.chat.dto.request.ChatRequest;
import com.engineeringmemory.chat.dto.response.ChatResponse;
import com.engineeringmemory.chat.dto.response.ChatResponse.Source;
import com.engineeringmemory.chat.enums.ChatStatus;
import com.engineeringmemory.common.exception.ErrorCode;
import com.engineeringmemory.conversation.service.ConversationService;
import com.engineeringmemory.conversation.service.ConversationService.HistoryMessage;
import com.engineeringmemory.conversation.service.ConversationService.Turn;
import com.engineeringmemory.knowledge.dto.SearchScope;
import com.engineeringmemory.llm.dto.LlmRequest;
import com.engineeringmemory.llm.service.LlmService;
import com.engineeringmemory.rag.dto.RagContext;
import com.engineeringmemory.rag.dto.RetrievalResult;
import com.engineeringmemory.rag.service.RagService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatOrchestrator {

	private static final int RETRIEVAL_HISTORY_CHARS = 2400;

	private final RagService ragService;
	private final LlmService llmService;
	private final ConversationService conversationService;

	public void narrate(long ownerId, String requestId, ChatRequest request, AnswerSink sink) {
		SearchScope scope = request.searchScope();
		Turn turn = conversationService.beginTurn(
				ownerId, request.conversationId(), request.message(), requestId);
		StringBuilder answer = new StringBuilder(1024);
		boolean persisted = false;

		try {
			String retrievalQuery = retrievalQuery(request.message(), turn.history());
			RetrievalResult retrieval = ragService.retrieve(ownerId, retrievalQuery, scope);

			if (!retrieval.hasContext()) {
				String noContext = ErrorCode.NO_CONTEXT.getDefaultMessage();
				sink.meta(turn.conversationId(), scope.describe(), List.of(), null);
				sink.delta(noContext);
				conversationService.completeTurn(ownerId, turn.assistantMessageId(), noContext, List.of(), true);
				persisted = true;
				sink.done(ChatStatus.NO_CONTEXT);
				return;
			}

			RagContext context = ragService.buildContext(
					request.message(), retrieval.chunks(), turn.history());
			List<Source> sources = context.sources().stream().map(Source::from).toList();
			sink.meta(turn.conversationId(), scope.describe(), sources, llmService.generationModel());

			if (sink.incremental()) {
				llmService.generateStream(LlmRequest.of(context.prompt()), delta -> {
					answer.append(delta);
					sink.delta(delta);
				});
			} else {
				String completed = llmService.generate(LlmRequest.of(context.prompt())).text();
				answer.append(completed);
				sink.delta(completed);
			}

			conversationService.completeTurn(
					ownerId, turn.assistantMessageId(), answer.toString(), sources, false);
			persisted = true;
			sink.done(ChatStatus.COMPLETED);
		} catch (RuntimeException e) {
			if (!persisted) {
				conversationService.failTurn(ownerId, turn.assistantMessageId());
			}
			throw new ChatTurnException(turn.conversationId(), e);
		}
	}

	public ChatResponse answer(long ownerId, String requestId, ChatRequest request) {
		CollectingAnswerSink sink = new CollectingAnswerSink(requestId);
		narrate(ownerId, requestId, request, sink);
		return sink.toResponse();
	}

	static String retrievalQuery(String question, List<HistoryMessage> history) {
		if (history == null || history.isEmpty()) {
			return question;
		}
		StringBuilder query = new StringBuilder(question).append("\n최근 대화:\n");
		for (int i = history.size() - 1; i >= 0; i--) {
			String line = history.get(i).content();
			if (line == null || line.isBlank()) {
				continue;
			}
			int remaining = RETRIEVAL_HISTORY_CHARS - query.length();
			if (remaining <= 0) {
				break;
			}
			query.append(line, 0, Math.min(line.length(), remaining)).append('\n');
		}
		return query.toString();
	}
}
