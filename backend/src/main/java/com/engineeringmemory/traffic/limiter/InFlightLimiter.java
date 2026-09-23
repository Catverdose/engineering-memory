package com.engineeringmemory.traffic.limiter;

import java.util.concurrent.atomic.AtomicInteger;

public final class InFlightLimiter {

	private final int max;
	private final AtomicInteger inFlight = new AtomicInteger();

	public InFlightLimiter(int max) {
		if (max < 1) {
			throw new IllegalArgumentException("max 는 1 이상이어야 합니다. max=" + max);
		}
		this.max = max;
	}

	public boolean tryAcquire() {
		while (true) {
			int current = inFlight.get();
			if (current >= max) {
				return false;
			}
			if (inFlight.compareAndSet(current, current + 1)) {
				return true;
			}
		}
	}

	public void release() {
		if (inFlight.decrementAndGet() < 0) {
			inFlight.incrementAndGet();
			throw new IllegalStateException("잡지 않은 자리를 반납했습니다. 반납이 중복되지 않았는지 확인하세요.");
		}
	}

	public int inFlight() {
		return inFlight.get();
	}

	public int max() {
		return max;
	}
}
