package com.engineeringmemory.rag.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.engineeringmemory.aiconfig.config.RagProperties;
import com.engineeringmemory.embedding.service.EmbeddingService;
import com.engineeringmemory.knowledge.dto.ChunkHit;
import com.engineeringmemory.knowledge.dto.SearchScope;
import com.engineeringmemory.knowledge.repository.DocumentChunkVectorRepository;
import com.engineeringmemory.rag.dto.RetrievalResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

	private final EmbeddingService embeddingService;
	private final DocumentChunkVectorRepository vectorRepository;
	private final RagProperties properties;

	public RetrievalResult retrieve(long ownerId, String query, SearchScope scope) {
		float[] vector = embeddingService.embedQuery(query);
		int candidateLimit = Math.min(200, properties.topK() * 4);
		List<ChunkHit> candidates = vectorRepository.search(
				ownerId, vector, embeddingService.embeddingModel(), scope, candidateLimit);

		Map<Long, Integer> perDocument = new HashMap<>();
		List<ChunkHit> accepted = new ArrayList<>();
		int contextChars = 0;
		for (ChunkHit hit : candidates) {
			if (hit.similarity() < properties.similarityThreshold()) {
				continue;
			}
			int count = perDocument.getOrDefault(hit.documentId(), 0);
			if (count >= properties.maxChunksPerDocument()) {
				continue;
			}
			if (!accepted.isEmpty() && contextChars + hit.content().length() > properties.maxPromptChars()) {
				continue;
			}
			accepted.add(hit);
			perDocument.put(hit.documentId(), count + 1);
			contextChars += hit.content().length();
			if (accepted.size() >= properties.topK()) {
				break;
			}
		}

		double best = candidates.isEmpty() ? 0.0 : candidates.get(0).similarity();
		log.debug("개인 지식 검색 완료: ownerId={}, candidates={}, accepted={}, bestSimilarity={}, scope={}",
				ownerId, candidates.size(), accepted.size(), String.format("%.4f", best), scope.describe());
		return RetrievalResult.of(accepted);
	}
}
