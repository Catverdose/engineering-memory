package com.engineeringmemory.traffic.limiter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InFlightLimiterTest {

	@Test
	@DisplayName("상한까지는 잡히고 그 다음은 기다리지 않고 거절된다")
	void tryAcquire_allowsUpToMax_thenRejects() {
		InFlightLimiter limiter = new InFlightLimiter(2);

		assertThat(limiter.tryAcquire()).isTrue();
		assertThat(limiter.tryAcquire()).isTrue();
		assertThat(limiter.tryAcquire()).isFalse();
		assertThat(limiter.inFlight()).isEqualTo(2);
	}

	@Test
	@DisplayName("반납하면 다시 잡을 수 있다")
	void release_freesSlot() {
		InFlightLimiter limiter = new InFlightLimiter(1);
		limiter.tryAcquire();

		limiter.release();

		assertThat(limiter.inFlight()).isZero();
		assertThat(limiter.tryAcquire()).isTrue();
	}

	@Test
	@DisplayName("잡지 않은 자리를 반납하면 크게 실패하고 상한은 늘어나지 않는다")
	void release_throws_whenNothingIsHeld() {
		InFlightLimiter limiter = new InFlightLimiter(1);

		assertThatThrownBy(limiter::release).isInstanceOf(IllegalStateException.class);

		assertThat(limiter.tryAcquire()).isTrue();
		assertThat(limiter.tryAcquire()).isFalse();
	}

	@Test
	@DisplayName("상한은 1 이상이어야 한다")
	void constructor_rejectsNonPositiveMax() {
		assertThatThrownBy(() -> new InFlightLimiter(0)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("여러 스레드가 동시에 잡아도 상한을 넘지 않는다")
	void tryAcquire_neverExceedsMax_underContention() {
		InFlightLimiter limiter = new InFlightLimiter(5);
		int threads = 32;
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger acquired = new AtomicInteger();

		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			for (int i = 0; i < threads; i++) {
				pool.submit(() -> {
					start.await();
					if (limiter.tryAcquire()) {
						acquired.incrementAndGet();
					}
					return null;
				});
			}
			start.countDown();
		}

		assertThat(acquired).hasValue(5);
		assertThat(limiter.inFlight()).isEqualTo(5);
	}
}
