package com.engineeringmemory.knowledge.dto.response;

import java.util.List;

public record KnowledgeFacetsResponse(
		List<DocumentTypeFacet> documentTypes,
		List<String> projects,
		List<String> technologies,
		List<String> tags) {

	public record DocumentTypeFacet(String code, String label, long count) {
	}
}
