package com.engineeringmemory.rag.dto;

import java.util.List;

import com.engineeringmemory.knowledge.dto.ChunkHit;

public record RetrievalResult(List<ChunkHit> chunks) {

	private static final RetrievalResult EMPTY = new RetrievalResult(List.of());

	public RetrievalResult {
		chunks = List.copyOf(chunks);
	}

	public static RetrievalResult empty() {
		return EMPTY;
	}

	public static RetrievalResult of(List<ChunkHit> chunks) {
		if (chunks == null || chunks.isEmpty()) {
			return EMPTY;
		}
		return new RetrievalResult(chunks);
	}

	public boolean hasContext() {
		return !chunks.isEmpty();
	}
}
