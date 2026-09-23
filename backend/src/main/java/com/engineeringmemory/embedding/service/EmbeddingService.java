package com.engineeringmemory.embedding.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.engineeringmemory.llm.service.LlmService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class EmbeddingService {

	private final LlmService llmService;

	public float[] embedQuery(String query) {
		return embedOne(query);
	}

	public float[] embedDocument(String text) {
		return embedOne(text);
	}

	public List<float[]> embedDocuments(List<String> texts) {
		if (texts == null || texts.isEmpty()) {
			return List.of();
		}
		List<float[]> vectors = llmService.embed(List.copyOf(texts));
		if (vectors.size() != texts.size()) {
			throw new IllegalStateException(
					"임베딩 결과 수가 입력 수와 다릅니다. input=%d, output=%d"
							.formatted(texts.size(), vectors.size()));
		}
		return List.copyOf(vectors);
	}

	public String embeddingModel() {
		return llmService.embeddingModel();
	}

	private float[] embedOne(String text) {
		List<float[]> result = llmService.embed(List.of(text));
		if (result.isEmpty()) {
			throw new IllegalStateException("임베딩 결과가 비어 있습니다.");
		}
		return result.get(0);
	}

}
