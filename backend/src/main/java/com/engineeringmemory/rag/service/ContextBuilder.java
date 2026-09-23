package com.engineeringmemory.rag.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Service;

import com.engineeringmemory.aiconfig.config.OllamaProperties;
import com.engineeringmemory.aiconfig.config.RagProperties;
import com.engineeringmemory.conversation.service.ConversationService.HistoryMessage;
import com.engineeringmemory.knowledge.dto.ChunkHit;
import com.engineeringmemory.rag.dto.RagContext;

@Service
public class ContextBuilder {

	static final int MAX_UTF8_BYTES_PER_UTF16_CHAR = 3;
	static final int MODEL_CONTROL_TOKEN_RESERVE = 64;

	private static final int MAX_QUESTION_CHARS = 1000;
	private static final int MAX_HISTORY_MESSAGE_CHARS = 1500;
	private static final int MAX_CITATION_CHARS = 500;
	private static final int MIN_KNOWLEDGE_CONTENT_CHARS = 32;

	private static final String SYSTEM_HEADER = "[System]\n";
	private static final String KNOWLEDGE_HEADER = "[Personal Knowledge]\n";
	private static final String HISTORY_HEADER = "\n[Recent Conversation]\n";
	private static final String QUESTION_HEADER = "\n[User Question]\n";

	public static final String SYSTEM_PROMPT = """
			당신은 사용자의 개인 개발 기록을 다시 찾고 연결하는 Personal Engineering Assistant입니다.
			아래 [Personal Knowledge]에 포함된 내용만 사용자의 과거 경험과 결정으로 말하세요.
			자료에 없는 내용을 사용자가 실제로 겪었거나 결정한 것처럼 추측하지 마세요.
			근거가 서로 다르면 차이를 숨기지 말고 문서와 시점을 구분하세요.
			질문에 직접 답하고, 원인·해결 방법·선택 이유가 근거에 있으면 함께 정리하세요.
			출처 목록은 화면이 별도로 표시하므로 답변 본문에 가짜 문서명이나 URL을 만들지 마세요.
			한국어 존댓말의 간결한 평문으로 답하고 마크다운 기호는 쓰지 마세요.""";

	private final int maxPromptChars;

	public ContextBuilder(RagProperties ragProperties, OllamaProperties ollamaProperties) {
		this.maxPromptChars = ragProperties.maxPromptChars();
		long worstCaseTokens = (long) maxPromptChars * MAX_UTF8_BYTES_PER_UTF16_CHAR
				+ ollamaProperties.numPredict() + MODEL_CONTROL_TOKEN_RESERVE;
		if (worstCaseTokens > ollamaProperties.numCtx()) {
			throw new IllegalStateException(
					"프롬프트 예산이 Ollama 컨텍스트를 초과합니다. "
							+ "worstCaseTokens=" + worstCaseTokens
							+ ", numCtx=" + ollamaProperties.numCtx());
		}
	}

	public RagContext build(String question, List<ChunkHit> hits, List<HistoryMessage> history) {
		if (hits == null || hits.isEmpty()) {
			throw new IllegalArgumentException("근거 청크 없이 생성 프롬프트를 만들 수 없습니다.");
		}

		String normalizedQuestion = boundedQuestion(question);
		String systemSection = SYSTEM_HEADER + SYSTEM_PROMPT + "\n\n";
		String questionSection = QUESTION_HEADER + normalizedQuestion;
		int knowledgeBudget = maxPromptChars
				- systemSection.length() - KNOWLEDGE_HEADER.length() - questionSection.length();
		if (knowledgeBudget < MIN_KNOWLEDGE_CONTENT_CHARS) {
			throw new IllegalStateException("프롬프트 상한이 시스템 지시문과 질문을 담기에 너무 작습니다.");
		}

		KnowledgeSection knowledge = buildKnowledge(hits, knowledgeBudget);
		int historyBudget = maxPromptChars
				- systemSection.length() - KNOWLEDGE_HEADER.length()
				- knowledge.text().length() - questionSection.length();
		String historySection = buildHistory(history, historyBudget);

		String prompt = systemSection + KNOWLEDGE_HEADER + knowledge.text()
				+ historySection + questionSection;
		if (prompt.length() > maxPromptChars) {
			throw new IllegalStateException("프롬프트 예산 계산 오류: " + prompt.length());
		}
		return new RagContext(prompt, knowledge.sources());
	}

	private KnowledgeSection buildKnowledge(List<ChunkHit> hits, int budget) {
		StringBuilder text = new StringBuilder(Math.min(budget, 4096));
		List<ChunkHit> included = new ArrayList<>();

		for (ChunkHit hit : hits) {
			if (hit == null || hit.content() == null || hit.content().isBlank()) {
				continue;
			}
			String citation = truncate(hit.citation(), MAX_CITATION_CHARS);
			String prefix = "Source " + (included.size() + 1) + ": " + citation + '\n';
			String content = hit.content().strip();
			int contentBudget = budget - text.length() - prefix.length() - 2;
			if (contentBudget < MIN_KNOWLEDGE_CONTENT_CHARS) {
				break;
			}

			String boundedContent = truncate(content, contentBudget);
			text.append(prefix).append(boundedContent).append("\n\n");
			included.add(hit);
			if (boundedContent.length() < content.length()) {
				break;
			}
		}

		if (included.isEmpty()) {
			throw new IllegalStateException("프롬프트 예산 안에 넣을 수 있는 지식 근거가 없습니다.");
		}
		return new KnowledgeSection(text.toString(), List.copyOf(included));
	}

	private String buildHistory(List<HistoryMessage> history, int budget) {
		if (history == null || history.isEmpty() || budget <= HISTORY_HEADER.length()) {
			return "";
		}

		int bodyBudget = budget - HISTORY_HEADER.length();
		int used = 0;
		List<String> newestFirst = new ArrayList<>();
		for (int i = history.size() - 1; i >= 0; i--) {
			HistoryMessage message = history.get(i);
			if (message == null || message.content() == null || message.content().isBlank()) {
				continue;
			}
			String role = truncate(message.role() == null ? "UNKNOWN" : message.role(), 20);
			String prefix = role + ": ";
			int contentBudget = bodyBudget - used - prefix.length() - 1;
			if (contentBudget <= 1) {
				break;
			}
			String content = truncate(message.content().strip(), MAX_HISTORY_MESSAGE_CHARS);
			String boundedContent = truncate(content, contentBudget);
			String line = prefix + boundedContent + '\n';
			newestFirst.add(line);
			used += line.length();
			if (boundedContent.length() < content.length()) {
				break;
			}
		}
		if (newestFirst.isEmpty()) {
			return "";
		}
		Collections.reverse(newestFirst);
		return HISTORY_HEADER + String.join("", newestFirst);
	}

	private static String boundedQuestion(String question) {
		if (question == null || question.isBlank()) {
			throw new IllegalArgumentException("질문이 비어 있습니다.");
		}
		return truncate(question.strip(), MAX_QUESTION_CHARS);
	}

	private static String truncate(String value, int maxChars) {
		if (value.length() <= maxChars) {
			return value;
		}
		if (maxChars <= 1) {
			return "…".substring(0, Math.max(0, maxChars));
		}
		return value.substring(0, maxChars - 1) + '…';
	}

	private record KnowledgeSection(String text, List<ChunkHit> sources) {
	}
}
