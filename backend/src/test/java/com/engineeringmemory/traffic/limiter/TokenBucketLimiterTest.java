package com.engineeringmemory.traffic.limiter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TokenBucketLimiterTest {

	private static final int CAPACITY = 3;
	private static final long REFILL_NANOS = Duration.ofSeconds(6).toNanos();
	private static final String CLIENT = "10.0.0.1";

	private static TokenBucketLimiter limiter() {
		return new TokenBucketLimiter(CAPACITY, REFILL_NANOS, 100);
	}

	@Test
	@DisplayName("capacity 만큼은 즉시 통과한다 (버스트 허용)")
	void tryAcquire_allowsUpToCapacity_atSameInstant() {
		TokenBucketLimiter limiter = limiter();

		for (int i = 0; i < CAPACITY; i++) {
			assertThat(limiter.tryAcquire(CLIENT, 0L))
					.as("%d번째 요청", i + 1)
					.isTrue();
		}
	}

	@Test
	@DisplayName("capacity 를 넘으면 거절한다")
	void tryAcquire_rejects_whenBucketIsEmpty() {
		TokenBucketLimiter limiter = limiter();
		for (int i = 0; i < CAPACITY; i++) {
			limiter.tryAcquire(CLIENT, 0L);
		}

		assertThat(limiter.tryAcquire(CLIENT, 0L)).isFalse();
	}

	@Test
	@DisplayName("refillPeriod 가 지나면 1회가 다시 허용된다")
	void tryAcquire_allowsOneMore_afterRefillPeriod() {
		TokenBucketLimiter limiter = limiter();
		for (int i = 0; i < CAPACITY; i++) {
			limiter.tryAcquire(CLIENT, 0L);
		}

		assertThat(limiter.tryAcquire(CLIENT, REFILL_NANOS - 1)).isFalse();
		assertThat(limiter.tryAcquire(CLIENT, REFILL_NANOS)).isTrue();
		assertThat(limiter.tryAcquire(CLIENT, REFILL_NANOS)).isFalse();
	}

	@Test
	@DisplayName("오래 쉬어도 capacity 를 넘겨 쌓이지 않는다")
	void tryAcquire_doesNotAccumulateBeyondCapacity() {
		TokenBucketLimiter limiter = limiter();
		limiter.tryAcquire(CLIENT, 0L);

		long muchLater = REFILL_NANOS * 100;
		for (int i = 0; i < CAPACITY; i++) {
			assertThat(limiter.tryAcquire(CLIENT, muchLater)).isTrue();
		}
		assertThat(limiter.tryAcquire(CLIENT, muchLater)).isFalse();
	}

	@Test
	@DisplayName("클라이언트마다 버킷이 따로다. 한 명이 다 써도 다른 사람은 영향받지 않는다")
	void tryAcquire_isolatesClients() {
		TokenBucketLimiter limiter = limiter();
		for (int i = 0; i < CAPACITY; i++) {
			limiter.tryAcquire("10.0.0.1", 0L);
		}

		assertThat(limiter.tryAcquire("10.0.0.1", 0L)).isFalse();
		assertThat(limiter.tryAcquire("10.0.0.2", 0L)).isTrue();
	}

	@Test
	@DisplayName("추적 대상이 상한에 닿으면 놀고 있는 버킷을 지운다")
	void tryAcquire_evictsIdleBuckets_whenAtMaxClients() {
		TokenBucketLimiter limiter = new TokenBucketLimiter(CAPACITY, REFILL_NANOS, 3);

		limiter.tryAcquire("a", 0L);
		limiter.tryAcquire("b", 0L);
		limiter.tryAcquire("c", 0L);
		assertThat(limiter.trackedClients()).isEqualTo(3);

		long afterFullRefill = REFILL_NANOS * CAPACITY;
		limiter.tryAcquire("d", afterFullRefill);

		assertThat(limiter.trackedClients()).isEqualTo(1);
	}

	@Test
	@DisplayName("상한이 찼고 idle 항목도 없으면 새 클라이언트를 거절하고 맵을 키우지 않는다")
	void tryAcquire_rejectsNewClient_withoutGrowingPastMaxClients() {
		TokenBucketLimiter limiter = new TokenBucketLimiter(CAPACITY, REFILL_NANOS, 2);

		assertThat(limiter.tryAcquire("a", 0L)).isTrue();
		assertThat(limiter.tryAcquire("b", 0L)).isTrue();
		assertThat(limiter.tryAcquire("attacker-controlled-new-key", 0L)).isFalse();
		assertThat(limiter.trackedClients()).isEqualTo(2);

		assertThat(limiter.tryAcquire("a", 0L)).isTrue();
	}

	@Test
	@DisplayName("정리는 동작을 바꾸지 않는다. 지워진 클라이언트는 가득 찬 상태로 다시 시작한다")
	void evictedClient_startsFull_whichMatchesItsIdleState() {
		TokenBucketLimiter limiter = new TokenBucketLimiter(CAPACITY, REFILL_NANOS, 2);
		limiter.tryAcquire("a", 0L);
		limiter.tryAcquire("b", 0L);

		long afterFullRefill = REFILL_NANOS * CAPACITY;
		limiter.tryAcquire("c", afterFullRefill);

		for (int i = 0; i < CAPACITY; i++) {
			assertThat(limiter.tryAcquire("a", afterFullRefill)).isTrue();
		}
		assertThat(limiter.tryAcquire("a", afterFullRefill)).isFalse();
	}

	@Test
	@DisplayName("capacity 가 0 이하이면 만들 수 없다")
	void constructor_throws_whenCapacityIsNotPositive() {
		assertThatThrownBy(() -> new TokenBucketLimiter(0, REFILL_NANOS, 100))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("capacity");
	}

	@Test
	@DisplayName("refillPeriod 가 0 이하이면 만들 수 없다 (0으로 나누기 방지)")
	void constructor_throws_whenRefillPeriodIsNotPositive() {
		assertThatThrownBy(() -> new TokenBucketLimiter(CAPACITY, 0, 100))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("refillPeriodNanos");
	}

	@Test
	@DisplayName("시간이 뒤로 가도 토큰이 늘지 않는다")
	void tryAcquire_doesNotGrantTokens_whenClockGoesBackwards() {
		TokenBucketLimiter limiter = limiter();
		long start = REFILL_NANOS * 10;
		for (int i = 0; i < CAPACITY; i++) {
			limiter.tryAcquire(CLIENT, start);
		}

		assertThat(limiter.tryAcquire(CLIENT, start - REFILL_NANOS)).isFalse();
	}
}
