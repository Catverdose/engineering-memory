package com.engineeringmemory.llm.client;

import java.util.List;

import com.engineeringmemory.aiconfig.enums.LlmProvider;

public interface EmbeddingClient {

	LlmProvider provider();

	List<float[]> embed(List<String> inputs);

	boolean isEmbeddingAvailable();

	String embeddingModel();
}
