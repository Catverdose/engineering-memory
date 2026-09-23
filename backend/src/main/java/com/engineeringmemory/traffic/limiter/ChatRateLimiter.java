package com.engineeringmemory.traffic.limiter;

import org.springframework.stereotype.Component;

import com.engineeringmemory.traffic.config.RateLimitProperties;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ChatRateLimiter {

	private final RateLimitProperties properties;
	private final TokenBucketLimiter limiter;

	public ChatRateLimiter(RateLimitProperties properties) {
		this.properties = properties;
		this.limiter = new TokenBucketLimiter(
				properties.capacity(),
				properties.refillPeriod().toNanos(),
				properties.maxClients());

		log.info("상담 요청 제한: enabled={}, capacity={}, refillPeriod={} (HTTP 와 WebSocket 이 함께 쓴다)",
				properties.enabled(), properties.capacity(), properties.refillPeriod());
	}

	public boolean tryAcquire(String clientKey) {
		if (!properties.enabled()) {
			return true;
		}
		return limiter.tryAcquire(clientKey, System.nanoTime());
	}

	public boolean enabled() {
		return properties.enabled();
	}

	public long retryAfterSeconds() {
		return properties.retryAfterSeconds();
	}

	public void logRejected(String transport, String clientKey) {
		log.warn("요청 제한 초과: transport={}, clientHash={}, trackedClients={}",
				transport, Integer.toHexString(clientKey.hashCode()), limiter.trackedClients());
	}
}
