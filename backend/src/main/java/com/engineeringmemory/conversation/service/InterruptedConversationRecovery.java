package com.engineeringmemory.conversation.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.engineeringmemory.conversation.repository.ChatMessageRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class InterruptedConversationRecovery implements ApplicationRunner {

	private final ChatMessageRepository messageRepository;

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		int recovered = messageRepository.failInterruptedGeneratingMessages();
		if (recovered > 0) {
			log.warn("재기동 전에 중단된 대화 메시지를 실패 상태로 회수했습니다: count={}", recovered);
		}
	}
}
