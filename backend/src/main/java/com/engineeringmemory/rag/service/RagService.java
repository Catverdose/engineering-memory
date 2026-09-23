package com.engineeringmemory.rag.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.engineeringmemory.conversation.service.ConversationService.HistoryMessage;
import com.engineeringmemory.knowledge.dto.ChunkHit;
import com.engineeringmemory.knowledge.dto.SearchScope;
import com.engineeringmemory.rag.dto.RagContext;
import com.engineeringmemory.rag.dto.RetrievalResult;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RagService {

	private final RetrievalService retrievalService;
	private final ContextBuilder contextBuilder;

	public RetrievalResult retrieve(long ownerId, String query, SearchScope scope) {
		return retrievalService.retrieve(ownerId, query, scope);
	}

	public RagContext buildContext(String question, List<ChunkHit> hits, List<HistoryMessage> history) {
		return contextBuilder.build(question, hits, history);
	}
}
