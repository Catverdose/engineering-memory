package com.engineeringmemory.traffic.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Validated
@ConfigurationProperties(prefix = "rate-limit.login")
public record LoginRateLimitProperties(
		@DefaultValue("true") boolean enabled,
		@Positive @Max(100) @DefaultValue("5") int capacity,
		@NotNull @DefaultValue("60s") Duration refillPeriod,
		@Positive @Max(1_000_000) @DefaultValue("10000") int maxClients) {

	public long retryAfterSeconds() {
		return Math.max(1, refillPeriod.toSeconds());
	}
}
