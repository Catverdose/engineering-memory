package com.engineeringmemory.knowledge.service;

public record ExtractedDocument(
		String content,
		byte[] originalBytes,
		String sourceName,
		String mediaType) {

	public ExtractedDocument {
		originalBytes = originalBytes.clone();
	}

	@Override
	public byte[] originalBytes() {
		return originalBytes.clone();
	}
}
