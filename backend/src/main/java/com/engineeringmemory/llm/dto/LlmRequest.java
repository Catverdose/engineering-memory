package com.engineeringmemory.llm.dto;

public record LlmRequest(String prompt, Double temperature) {

	public LlmRequest {
		if (prompt == null || prompt.isBlank()) {
			throw new IllegalArgumentException("프롬프트가 비어 있습니다. 근거 없이 모델을 부르지 않는다.");
		}
		if (temperature != null && (temperature < 0.0 || temperature > 2.0)) {
			throw new IllegalArgumentException("온도는 0.0 ~ 2.0 이어야 합니다. temperature=" + temperature);
		}
	}

	public static LlmRequest of(String prompt) {
		return new LlmRequest(prompt, null);
	}

	public LlmRequest withTemperature(double temperature) {
		return new LlmRequest(prompt, temperature);
	}

	public boolean hasTemperature() {
		return temperature != null;
	}
}
