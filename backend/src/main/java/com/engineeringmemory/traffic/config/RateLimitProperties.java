package com.engineeringmemory.traffic.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Validated
@ConfigurationProperties(prefix = "rate-limit.chat")
public record RateLimitProperties(

		@DefaultValue("true") boolean enabled,

		@Positive @Max(10_000) @DefaultValue("10") int capacity,

		@NotNull @DefaultValue("6s") Duration refillPeriod,

		@Positive @Max(1_000_000) @DefaultValue("10000") int maxClients) {

	public long retryAfterSeconds() {
		return Math.max(1, refillPeriod.toSeconds());
	}
}
