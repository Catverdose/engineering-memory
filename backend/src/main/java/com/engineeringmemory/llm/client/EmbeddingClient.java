package com.engineeringmemory.llm.client;

import java.util.List;

import com.engineeringmemory.aiconfig.enums.LlmProvider;
import com.engineeringmemory.common.exception.BusinessException;

public interface EmbeddingClient {

	LlmProvider provider();

	List<float[]> embed(List<String> inputs);

	boolean isEmbeddingAvailable();

	String embeddingModel();
}
