package com.engineeringmemory.llm.workload;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import com.engineeringmemory.aiconfig.config.ModelConcurrencyProperties;
import com.engineeringmemory.traffic.limiter.InFlightLimiter;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ModelWorkloadGate {

	private final InFlightLimiter interactive;

	private final Duration backgroundYieldTimeout;

	private final Object idle = new Object();

	private final AtomicLong yieldTimeouts = new AtomicLong();

	private final AtomicLong yields = new AtomicLong();

	public ModelWorkloadGate(ModelConcurrencyProperties properties) {
		this.interactive = new InFlightLimiter(properties.maxInFlight());
		this.backgroundYieldTimeout = properties.backgroundYieldTimeout();
	}

	public boolean tryAcquireInteractive() {
		return interactive.tryAcquire();
	}

	public void releaseInteractive() {
		synchronized (idle) {
			interactive.release();
			if (interactive.inFlight() == 0) {
				idle.notifyAll();
			}
		}
	}

	public void yieldToInteractive() {
		long deadline = System.nanoTime() + backgroundYieldTimeout.toNanos();
		boolean waited = false;
		synchronized (idle) {
			while (interactive.inFlight() > 0) {
				long remainingNanos = deadline - System.nanoTime();
				if (remainingNanos <= 0) {
					yieldTimeouts.incrementAndGet();
					log.info("채팅이 계속 이어져 색인이 더 기다리지 않고 진행합니다: "
							+ "기다린시간={}ms, 진행중인채팅={}건",
							backgroundYieldTimeout.toMillis(), interactive.inFlight());
					return;
				}
				waited = true;
				try {
					idle.wait(Math.max(1L, remainingNanos / 1_000_000L));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
		if (waited) {
			yields.incrementAndGet();
			log.debug("채팅이 끝날 때까지 색인이 비켜섰습니다.");
		}
	}

	public int interactiveInFlight() {
		return interactive.inFlight();
	}

	public int maxInteractive() {
		return interactive.max();
	}

	public long yields() {
		return yields.get();
	}

	public long yieldTimeouts() {
		return yieldTimeouts.get();
	}
}
