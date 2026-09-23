package com.engineeringmemory.traffic.limiter;

import org.springframework.stereotype.Component;

import com.engineeringmemory.traffic.config.LoginRateLimitProperties;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class LoginRateLimiter {

	private final LoginRateLimitProperties properties;
	private final TokenBucketLimiter limiter;

	public LoginRateLimiter(LoginRateLimitProperties properties) {
		this.properties = properties;
		this.limiter = new TokenBucketLimiter(
				properties.capacity(), properties.refillPeriod().toNanos(), properties.maxClients());
	}

	public boolean enabled() {
		return properties.enabled();
	}

	public boolean tryAcquire(String clientKey) {
		return !properties.enabled() || limiter.tryAcquire(clientKey, System.nanoTime());
	}

	public long retryAfterSeconds() {
		return properties.retryAfterSeconds();
	}

	public void logRejected(String clientKey) {
		log.warn("로그인 요청 제한 초과: clientHash={}, trackedClients={}",
				Integer.toHexString(clientKey.hashCode()), limiter.trackedClients());
	}
}
