package com.engineeringmemory.aiconfig.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Validated
@ConfigurationProperties(prefix = "chat.model")
public record ModelConcurrencyProperties(

		@Positive @Max(10_000) @DefaultValue("50") int maxInFlight,

		@NotNull @DefaultValue("30s") Duration backgroundYieldTimeout) {
}
