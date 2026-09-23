package com.engineeringmemory.knowledge.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.engineeringmemory.knowledge.config.KnowledgeProperties;

@Component
public class DocumentChunker {

	private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.+?)\\s*$");

	private final KnowledgeProperties properties;

	public DocumentChunker(KnowledgeProperties properties) {
		this.properties = properties;
	}

	public List<ChunkDraft> split(String content) {
		List<Section> sections = sections(content);
		List<ChunkDraft> chunks = new ArrayList<>();
		for (Section section : sections) {
			appendSection(chunks, section);
			if (chunks.size() > properties.maxChunks()) {
				throw new IllegalArgumentException(
						"문서 청크가 최대 %,d개를 초과합니다.".formatted(properties.maxChunks()));
			}
		}
		if (chunks.isEmpty()) {
			throw new IllegalArgumentException("색인할 문서 청크가 없습니다.");
		}
		List<ChunkDraft> indexed = new ArrayList<>(chunks.size());
		for (int i = 0; i < chunks.size(); i++) {
			ChunkDraft chunk = chunks.get(i);
			indexed.add(new ChunkDraft(i, chunk.heading(), chunk.content()));
		}
		return List.copyOf(indexed);
	}

	private List<Section> sections(String content) {
		List<Section> sections = new ArrayList<>();
		String heading = null;
		StringBuilder body = new StringBuilder();
		for (String line : content.split("\\n", -1)) {
			Matcher matcher = HEADING.matcher(line);
			if (matcher.matches()) {
				flushSection(sections, heading, body);
				heading = matcher.group(1).strip();
				body.setLength(0);
			} else {
				body.append(line).append('\n');
			}
		}
		flushSection(sections, heading, body);
		return sections;
	}

	private static void flushSection(List<Section> sections, String heading, StringBuilder body) {
		String text = body.toString().strip();
		if (!text.isEmpty()) {
			sections.add(new Section(heading, text));
		}
	}

	private void appendSection(List<ChunkDraft> target, Section section) {
		String text = section.content();
		int start = 0;
		while (start < text.length()) {
			int hardEnd = Math.min(start + properties.chunkSizeChars(), text.length());
			int end = hardEnd == text.length() ? hardEnd : findBoundary(text, start, hardEnd);
			String chunk = text.substring(start, end).strip();
			if (!chunk.isEmpty()) {
				target.add(new ChunkDraft(-1, section.heading(), chunk));
			}
			if (end >= text.length()) {
				break;
			}
			int next = Math.max(start + 1, end - properties.chunkOverlapChars());
			while (next < end && Character.isWhitespace(text.charAt(next))) {
				next++;
			}
			start = next;
		}
	}

	private static int findBoundary(String text, int start, int hardEnd) {
		int minimum = start + ((hardEnd - start) / 2);
		for (int i = hardEnd; i > minimum; i--) {
			char c = text.charAt(i - 1);
			if (c == '\n' || c == ' ' || c == '\t') {
				return i;
			}
		}
		return hardEnd;
	}

	public record ChunkDraft(int index, String heading, String content) {
	}

	private record Section(String heading, String content) {
	}
}
