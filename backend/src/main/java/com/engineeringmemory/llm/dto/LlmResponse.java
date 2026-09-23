package com.engineeringmemory.llm.dto;

public record LlmResponse(String text, Integer tokenCount, Long durationMs) {

	public LlmResponse {
		if (text == null || text.isBlank()) {
			throw new IllegalArgumentException("생성 응답이 비어 있습니다.");
		}
		text = text.trim();
	}

	public static LlmResponse of(String text) {
		return new LlmResponse(text, null, null);
	}
}
