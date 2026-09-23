package com.engineeringmemory.rag.dto;

import java.util.List;

import com.engineeringmemory.knowledge.dto.ChunkHit;

public record RagContext(String prompt, List<ChunkHit> sources) {

	public RagContext {
		sources = List.copyOf(sources);
	}
}
