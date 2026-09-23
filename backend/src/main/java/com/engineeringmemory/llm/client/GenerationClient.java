package com.engineeringmemory.llm.client;

import java.util.function.Consumer;

import com.engineeringmemory.aiconfig.enums.LlmProvider;
import com.engineeringmemory.common.exception.BusinessException;
import com.engineeringmemory.llm.dto.LlmRequest;
import com.engineeringmemory.llm.dto.LlmResponse;

public interface GenerationClient {

	LlmProvider provider();

	LlmResponse generate(LlmRequest request);

	LlmResponse generateStream(LlmRequest request, Consumer<String> onDelta);

	boolean isGenerationAvailable();

	String generationModel();
}
