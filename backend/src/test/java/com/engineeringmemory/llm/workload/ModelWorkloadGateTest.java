package com.engineeringmemory.llm.workload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.engineeringmemory.aiconfig.config.ModelConcurrencyProperties;

class ModelWorkloadGateTest {

	private static ModelWorkloadGate gate(int maxInteractive, Duration yieldTimeout) {
		return new ModelWorkloadGate(new ModelConcurrencyProperties(maxInteractive, yieldTimeout));
	}

	@Test
	@DisplayName("채팅 자리가 차면 기다리게 하지 않고 즉시 거절한다")
	void interactiveIsRejectedWhenFull() {
		ModelWorkloadGate gate = gate(1, Duration.ofSeconds(1));

		assertThat(gate.tryAcquireInteractive()).isTrue();
		assertThat(gate.tryAcquireInteractive()).isFalse();
		assertThat(gate.interactiveInFlight()).isEqualTo(1);

		gate.releaseInteractive();
		assertThat(gate.tryAcquireInteractive()).isTrue();
	}

	@Test
	@DisplayName("잡지 않은 자리를 반납하면 드러낸다")
	void releasingUnheldSlotFails() {
		assertThatThrownBy(() -> gate(1, Duration.ofSeconds(1)).releaseInteractive())
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("채팅이 없으면 색인은 기다리지 않는다")
	void backgroundProceedsWhenNobodyIsWaiting() {
		ModelWorkloadGate gate = gate(1, Duration.ofMinutes(10));

		long startedAt = System.nanoTime();
		gate.yieldToInteractive();

		assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(1));
		assertThat(gate.yields()).isZero();
		assertThat(gate.yieldTimeouts()).isZero();
	}

	@Test
	@DisplayName("채팅이 걸려 있으면 색인은 비켜서고, 끝나면 곧바로 깨어난다")
	void backgroundWaitsForInteractiveAndWakesOnRelease() throws Exception {
		ModelWorkloadGate gate = gate(1, Duration.ofMinutes(10));
		assertThat(gate.tryAcquireInteractive()).isTrue();

		CountDownLatch passed = new CountDownLatch(1);
		Thread indexing = background(gate, passed);

		assertThat(passed.await(300, TimeUnit.MILLISECONDS))
				.as("채팅이 진행 중인데 색인이 통과했다")
				.isFalse();

		gate.releaseInteractive();

		assertThat(passed.await(5, TimeUnit.SECONDS))
				.as("채팅이 끝났는데 색인이 깨어나지 않았다")
				.isTrue();
		indexing.join(TimeUnit.SECONDS.toMillis(5));

		assertThat(gate.yields()).isEqualTo(1);
		assertThat(gate.yieldTimeouts()).isZero();
	}

	@Test
	@DisplayName("채팅이 여럿이면 마지막 하나가 끝나야 색인이 깨어난다")
	void backgroundWaitsUntilTheLastInteractiveLeaves() throws Exception {
		ModelWorkloadGate gate = gate(2, Duration.ofMinutes(10));
		assertThat(gate.tryAcquireInteractive()).isTrue();
		assertThat(gate.tryAcquireInteractive()).isTrue();

		CountDownLatch passed = new CountDownLatch(1);
		Thread indexing = background(gate, passed);

		gate.releaseInteractive();
		assertThat(passed.await(300, TimeUnit.MILLISECONDS)).isFalse();

		gate.releaseInteractive();
		assertThat(passed.await(5, TimeUnit.SECONDS)).isTrue();
		indexing.join(TimeUnit.SECONDS.toMillis(5));
	}

	@Test
	@DisplayName("대화가 끊이지 않아도 색인이 영원히 굶지는 않는다")
	void backgroundProceedsAfterTimeout() {
		ModelWorkloadGate gate = gate(1, Duration.ofMillis(200));
		assertThat(gate.tryAcquireInteractive()).isTrue();

		long startedAt = System.nanoTime();
		gate.yieldToInteractive();
		Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);

		assertThat(waited).isGreaterThanOrEqualTo(Duration.ofMillis(150));
		assertThat(waited).isLessThan(Duration.ofSeconds(5));
		assertThat(gate.yieldTimeouts()).isEqualTo(1);
		assertThat(gate.interactiveInFlight())
				.as("비켜서기는 채팅 자리를 건드리지 않는다")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("제한 시간이 1밀리초보다 짧아도 영원히 기다리지 않는다")
	void subMillisecondTimeoutDoesNotWaitForever() throws Exception {
		ModelWorkloadGate gate = gate(1, Duration.ofNanos(500_000));
		assertThat(gate.tryAcquireInteractive()).isTrue();

		CountDownLatch passed = new CountDownLatch(1);
		background(gate, passed);

		assertThat(passed.await(5, TimeUnit.SECONDS))
				.as("제한 시간이 무시돼 색인이 영원히 기다린다")
				.isTrue();
	}

	@Test
	@DisplayName("종료 신호가 오면 즉시 돌아가고 인터럽트 표시를 남긴다")
	void interruptEndsTheWaitAndKeepsTheFlag() throws Exception {
		ModelWorkloadGate gate = gate(1, Duration.ofMinutes(10));
		assertThat(gate.tryAcquireInteractive()).isTrue();

		AtomicBoolean interruptedOnReturn = new AtomicBoolean();
		CountDownLatch returned = new CountDownLatch(1);
		Thread indexing = new Thread(() -> {
			gate.yieldToInteractive();
			interruptedOnReturn.set(Thread.currentThread().isInterrupted());
			returned.countDown();
		});
		indexing.setDaemon(true);
		indexing.start();
		Thread.sleep(100);

		indexing.interrupt();

		assertThat(returned.await(5, TimeUnit.SECONDS))
				.as("종료 중인데 색인 스레드가 10분짜리 대기에 묶여 있다")
				.isTrue();
		assertThat(interruptedOnReturn).isTrue();
	}

	private static Thread background(ModelWorkloadGate gate, CountDownLatch passed) {
		Thread thread = new Thread(() -> {
			gate.yieldToInteractive();
			passed.countDown();
		});
		thread.setDaemon(true);
		thread.start();
		return thread;
	}
}
