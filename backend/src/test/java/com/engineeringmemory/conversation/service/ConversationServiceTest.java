package com.engineeringmemory.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import com.engineeringmemory.common.exception.BusinessException;
import com.engineeringmemory.common.exception.ErrorCode;
import com.engineeringmemory.conversation.dto.ConversationResponse;
import com.engineeringmemory.conversation.entity.ChatMessage;
import com.engineeringmemory.conversation.entity.Conversation;
import com.engineeringmemory.conversation.repository.ChatMessageRepository;
import com.engineeringmemory.conversation.repository.ConversationRepository;
import com.engineeringmemory.conversation.service.ConversationService.HistoryMessage;
import com.engineeringmemory.conversation.service.ConversationService.Turn;

class ConversationServiceTest {

	private static final long OWNER = 42L;
	private static final long OTHERS_CONVERSATION = 999L;

	@Test
	@DisplayName("문맥은 오래된 것부터 정렬한다")
	void historyIsOldestFirst() {
		Fixture fixture = new Fixture();
		fixture.existingConversation(5L);
		fixture.historyNewestFirst(
				completed(ChatMessage.Role.ASSISTANT, 4, "Lua 로 원자적으로 깎았다"),
				completed(ChatMessage.Role.USER, 3, "Redis 를 왜 썼지?"));

		Turn turn = fixture.service.beginTurn(OWNER, 5L, "그때 Kafka 는?", "req-1");

		assertThat(turn.history()).extracting(HistoryMessage::content)
				.containsExactly("Redis 를 왜 썼지?", "Lua 로 원자적으로 깎았다");
	}

	@Test
	@DisplayName("생성 중인 메시지는 문맥에 넣지 않는다")
	void excludesGeneratingMessagesFromHistory() {
		Fixture fixture = new Fixture();
		fixture.existingConversation(5L);
		fixture.historyNewestFirst(
				ChatMessage.assistant(5L, OWNER, 4, "req-0"),
				completed(ChatMessage.Role.USER, 3, "앞선 질문"));

		Turn turn = fixture.service.beginTurn(OWNER, 5L, "후속 질문", "req-1");

		assertThat(turn.history()).extracting(HistoryMessage::content).containsExactly("앞선 질문");
	}

	@Test
	@DisplayName("문맥은 최근 8건까지만 본다")
	void historyIsCapped() {
		Fixture fixture = new Fixture();
		fixture.existingConversation(5L);
		fixture.historyNewestFirst();

		fixture.service.beginTurn(OWNER, 5L, "질문", "req-1");

		ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
		verify(fixture.messages).findByConversationIdAndOwnerIdOrderBySequenceDesc(
				eq(5L), eq(OWNER), page.capture());

		assertThat(page.getValue().getPageSize()).isEqualTo(8);
	}

	@Test
	@DisplayName("질문과 빈 AI 메시지를 함께 만든다")
	void createsUserAndAssistantMessages() {
		Fixture fixture = new Fixture();
		fixture.existingConversation(5L);
		fixture.historyNewestFirst();

		fixture.service.beginTurn(OWNER, 5L, "질문", "req-1");

		ArgumentCaptor<ChatMessage> saved = ArgumentCaptor.forClass(ChatMessage.class);
		verify(fixture.messages, org.mockito.Mockito.times(2)).save(saved.capture());

		assertThat(saved.getAllValues()).extracting(ChatMessage::getRole)
				.containsExactly(ChatMessage.Role.USER, ChatMessage.Role.ASSISTANT);
		assertThat(saved.getAllValues().get(1).getStatus())
				.isEqualTo(ChatMessage.Status.GENERATING);
		assertThat(saved.getAllValues()).extracting(ChatMessage::getSequence)
				.as("순서가 어긋나면 대화가 뒤섞여 보인다")
				.containsExactly(1L, 2L);
	}

	@Test
	@DisplayName("대화 번호가 없으면 첫 질문으로 새 대화를 만든다")
	void startsNewConversationWhenIdIsAbsent() {
		Fixture fixture = new Fixture();
		fixture.savesNewConversation(11L);
		fixture.historyNewestFirst();

		Turn turn = fixture.service.beginTurn(OWNER, null, "첫 질문", "req-1");

		assertThat(turn.conversationId()).isEqualTo(11L);
		assertThat(turn.history()).isEmpty();
		verify(fixture.conversations, never()).findLocked(anyLong(), anyLong());
	}

	@Test
	@DisplayName("남의 대화는 없는 것처럼 답한다")
	void othersConversationLooksMissing() {
		Fixture fixture = new Fixture();
		when(fixture.conversations.findLocked(OTHERS_CONVERSATION, OWNER)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> fixture.service.beginTurn(OWNER, OTHERS_CONVERSATION, "질문", "req-1"))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
	}

	@Test
	@DisplayName("조회·수정·삭제 모두 소유자를 조건에 넣는다")
	void everyLookupCarriesOwner() {
		Fixture fixture = new Fixture();
		Conversation conversation = fixture.existingConversation(5L);
		when(fixture.conversations.findByIdAndOwnerId(5L, OWNER)).thenReturn(Optional.of(conversation));
		when(fixture.messages.findByConversationIdAndOwnerIdOrderBySequenceDesc(
				eq(5L), eq(OWNER), any())).thenReturn(List.of());

		fixture.service.get(OWNER, 5L, null, 20);
		fixture.service.rename(OWNER, 5L, "새 제목");
		fixture.service.delete(OWNER, 5L);

		verify(fixture.conversations).findByIdAndOwnerId(5L, OWNER);
		verify(fixture.conversations, org.mockito.Mockito.times(2)).findLocked(5L, OWNER);
		verify(fixture.messages).deleteByConversationIdAndOwnerId(5L, OWNER);
	}

	@Test
	@DisplayName("남의 AI 메시지는 완료 처리할 수 없다")
	void cannotCompleteAnotherOwnersMessage() {
		Fixture fixture = new Fixture();
		when(fixture.messages.findByIdAndOwnerId(77L, OWNER)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> fixture.service.completeTurn(OWNER, 77L, "답변", List.of(), false))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVARIANT_VIOLATION));
	}

	@Test
	@DisplayName("실패 기록은 대상이 없어도 조용히 넘어간다")
	void failTurnIsSilentWhenMessageIsGone() {
		Fixture fixture = new Fixture();
		when(fixture.messages.findByIdAndOwnerId(anyLong(), anyLong())).thenReturn(Optional.empty());

		assertThatCode(() -> fixture.service.failTurn(OWNER, 77L)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("근거 없음은 완료와 다른 상태로 남긴다")
	void noContextGetsItsOwnStatus() {
		Fixture fixture = new Fixture();
		ChatMessage assistant = ChatMessage.assistant(5L, OWNER, 2, "req-1");
		when(fixture.messages.findByIdAndOwnerId(77L, OWNER)).thenReturn(Optional.of(assistant));

		fixture.service.completeTurn(OWNER, 77L, "안내", List.of(), true);

		assertThat(assistant.getStatus()).isEqualTo(ChatMessage.Status.NO_CONTEXT);
	}

	@Test
	@DisplayName("한 건 더 읽어 다음 페이지가 있는지 판단한다")
	void detectsMorePagesByReadingOneExtra() {
		Fixture fixture = new Fixture();
		Conversation conversation = fixture.existingConversation(5L);
		when(fixture.conversations.findByIdAndOwnerId(5L, OWNER)).thenReturn(Optional.of(conversation));
		when(fixture.messages.findByConversationIdAndOwnerIdOrderBySequenceDesc(eq(5L), eq(OWNER), any()))
				.thenReturn(List.of(
						completed(ChatMessage.Role.USER, 3, "셋"),
						completed(ChatMessage.Role.USER, 2, "둘"),
						completed(ChatMessage.Role.USER, 1, "하나")));

		ConversationResponse response = fixture.service.get(OWNER, 5L, null, 2);

		ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
		verify(fixture.messages).findByConversationIdAndOwnerIdOrderBySequenceDesc(
				eq(5L), eq(OWNER), page.capture());

		assertThat(page.getValue().getPageSize()).isEqualTo(3);
		assertThat(response.messages()).hasSize(2);
		assertThat(response.messages()).extracting(ConversationResponse.Message::content)
				.as("화면은 오래된 것부터 읽는다")
				.containsExactly("둘", "셋");
	}

	@Test
	@DisplayName("이어 읽기는 지정한 순번 이전만 본다")
	void pagesBackwardsFromSequence() {
		Fixture fixture = new Fixture();
		Conversation conversation = fixture.existingConversation(5L);
		when(fixture.conversations.findByIdAndOwnerId(5L, OWNER)).thenReturn(Optional.of(conversation));
		when(fixture.messages.findByConversationIdAndOwnerIdAndSequenceLessThanOrderBySequenceDesc(
				eq(5L), eq(OWNER), eq(10L), any())).thenReturn(List.of());

		fixture.service.get(OWNER, 5L, 10L, 20);

		verify(fixture.messages).findByConversationIdAndOwnerIdAndSequenceLessThanOrderBySequenceDesc(
				eq(5L), eq(OWNER), eq(10L), any());
		verify(fixture.messages, never())
				.findByConversationIdAndOwnerIdOrderBySequenceDesc(anyLong(), anyLong(), any());
	}

	private static ChatMessage completed(ChatMessage.Role role, long sequence, String content) {
		return role == ChatMessage.Role.USER
				? ChatMessage.user(5L, OWNER, sequence, content, "req-0")
				: assistantCompleted(sequence, content);
	}

	private static ChatMessage assistantCompleted(long sequence, String content) {
		ChatMessage message = ChatMessage.assistant(5L, OWNER, sequence, "req-0");
		message.complete(content, List.of(), false);
		return message;
	}

	private static final class Fixture {
		final ConversationRepository conversations = mock(ConversationRepository.class);
		final ChatMessageRepository messages = mock(ChatMessageRepository.class);
		final ConversationService service = new ConversationService(conversations, messages);

		Conversation existingConversation(long id) {
			Conversation conversation = withId(Conversation.create(OWNER, "첫 질문"), id);
			when(conversations.findLocked(id, OWNER)).thenReturn(Optional.of(conversation));
			return conversation;
		}

		void savesNewConversation(long id) {
			when(conversations.save(any(Conversation.class)))
					.thenAnswer(invocation -> withId(invocation.getArgument(0), id));
		}

		void historyNewestFirst(ChatMessage... newestFirst) {
			when(messages.findByConversationIdAndOwnerIdOrderBySequenceDesc(anyLong(), anyLong(), any()))
					.thenReturn(List.of(newestFirst));
		}

		private static Conversation withId(Conversation conversation, long id) {
			try {
				Field field = Conversation.class.getDeclaredField("id");
				field.setAccessible(true);
				field.set(conversation, id);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
			return conversation;
		}
	}
}
