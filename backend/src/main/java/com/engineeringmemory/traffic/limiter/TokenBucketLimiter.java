package com.engineeringmemory.traffic.limiter;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TokenBucketLimiter {

	private final int capacity;
	private final long refillPeriodNanos;
	private final int maxClients;

	private final long idleUntilFullNanos;

	private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

	public TokenBucketLimiter(int capacity, long refillPeriodNanos, int maxClients) {
		if (capacity < 1) {
			throw new IllegalArgumentException("capacity 는 1 이상이어야 합니다. capacity=" + capacity);
		}
		if (refillPeriodNanos < 1) {
			throw new IllegalArgumentException(
					"refillPeriodNanos 는 1 이상이어야 합니다. refillPeriodNanos=" + refillPeriodNanos);
		}
		if (maxClients < 1) {
			throw new IllegalArgumentException("maxClients 는 1 이상이어야 합니다. maxClients=" + maxClients);
		}
		this.capacity = capacity;
		this.refillPeriodNanos = refillPeriodNanos;
		this.maxClients = maxClients;
		this.idleUntilFullNanos = refillPeriodNanos * capacity;
	}

	public synchronized boolean tryAcquire(String clientKey, long nowNanos) {
		Bucket existing = buckets.get(clientKey);
		if (existing != null) {
			Bucket updated = existing.refillAndConsume(nowNanos, capacity, refillPeriodNanos);
			buckets.put(clientKey, updated);
			return updated.allowed();
		}

		if (buckets.size() >= maxClients) {
			evictIdle(nowNanos);
		}

		if (buckets.size() >= maxClients) {
			return false;
		}

		buckets.put(clientKey, new Bucket(capacity - 1.0, nowNanos, true));
		return true;
	}

	public int trackedClients() {
		return buckets.size();
	}

	private void evictIdle(long nowNanos) {
		Iterator<Map.Entry<String, Bucket>> iterator = buckets.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<String, Bucket> entry = iterator.next();
			if (nowNanos - entry.getValue().lastRefillNanos >= idleUntilFullNanos) {
				buckets.remove(entry.getKey(), entry.getValue());
			}
		}
	}

	private record Bucket(double tokens, long lastRefillNanos, boolean allowed) {

		Bucket refillAndConsume(long nowNanos, int capacity, long refillPeriodNanos) {
			long elapsed = Math.max(0, nowNanos - lastRefillNanos);
			double refilled = Math.min(capacity, tokens + (double) elapsed / refillPeriodNanos);

			if (refilled >= 1.0) {
				return new Bucket(refilled - 1.0, nowNanos, true);
			}
			return new Bucket(refilled, nowNanos, false);
		}
	}
}
