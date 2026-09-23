package com.engineeringmemory.common.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BatchOutcomeTest {

	@Test
	@DisplayName("전부 성공했을 때만 완전 성공으로 판정한다")
	void isCompleteSuccess_isTrue_onlyWhenNothingFailedOrWasSkippedByStop() {
		BatchOutcome<Long> outcome = BatchOutcome.<Long>builder(2)
				.succeeded(1L)
				.succeeded(2L)
				.build();

		assertThat(outcome.isCompleteSuccess()).isTrue();
		assertThat(outcome.stoppedEarly()).isFalse();
		assertThat(outcome.stopReason()).isNull();
	}

	@Test
	@DisplayName("일부가 실패하면 완전 성공이 아니다")
	void isCompleteSuccess_isFalse_whenSomeItemsFailed() {
		BatchOutcome<Long> outcome = BatchOutcome.<Long>builder(2)
				.succeeded(1L)
				.failed(2L, "INDEXING_FAILED", "임베딩 생성에 실패했습니다.")
				.build();

		assertThat(outcome.isCompleteSuccess()).isFalse();
		assertThat(outcome.succeeded()).containsExactly(1L);
		assertThat(outcome.failed()).hasSize(1);
	}

	@Test
	@DisplayName("실패 항목마다 오류 코드를 남겨 로그 파싱 없이 원인을 구분할 수 있다")
	void failure_carriesMachineReadableErrorCode() {
		BatchOutcome<Long> outcome = BatchOutcome.<Long>builder(1)
				.failed(7L, "INDEXING_FAILED", "임베딩 생성에 실패했습니다.")
				.build();

		BatchOutcome.Failure<Long> failure = outcome.failed().get(0);
		assertThat(failure.item()).isEqualTo(7L);
		assertThat(failure.errorCode()).isEqualTo("INDEXING_FAILED");
		assertThat(failure.message()).isNotBlank();
	}

	@Test
	@DisplayName("중단된 배치는 미시도 항목과 중단 사유를 함께 보고한다")
	void stoppedBatch_reportsNotAttemptedItemsAndReason() {
		BatchOutcome<Long> outcome = BatchOutcome.<Long>builder(5)
				.succeeded(1L)
				.failed(2L, "INDEXING_FAILED", "실패")
				.notAttempted(List.of(3L, 4L, 5L))
				.stoppedBecause("연속 실패 1회로 중단했습니다.")
				.build();

		assertThat(outcome.isCompleteSuccess()).isFalse();
		assertThat(outcome.stoppedEarly()).isTrue();
		assertThat(outcome.stopReason()).contains("연속 실패");
		assertThat(outcome.notAttempted()).containsExactly(3L, 4L, 5L);
		assertThat(outcome.failed()).hasSize(1);
	}

	@Test
	@DisplayName("의도적으로 건너뛴 항목은 실패로 세지 않는다")
	void skippedItems_areNotCountedAsFailures() {
		BatchOutcome<Long> outcome = BatchOutcome.<Long>builder(2)
				.succeeded(1L)
				.skipped(2L)
				.build();

		assertThat(outcome.skipped()).containsExactly(2L);
		assertThat(outcome.failed()).isEmpty();
		assertThat(outcome.isCompleteSuccess()).isTrue();
	}

	@Test
	@DisplayName("결과 목록은 수정할 수 없다")
	void resultLists_areImmutable() {
		BatchOutcome<Long> outcome = BatchOutcome.<Long>builder(1).succeeded(1L).build();

		assertThat(outcome.succeeded()).isUnmodifiable();
		assertThat(outcome.failed()).isUnmodifiable();
	}
}
