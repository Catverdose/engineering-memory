package com.engineeringmemory.aiconfig.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "llm.observability")
public record ModelObservabilityProperties(

		@NotNull @DefaultValue("2s") Duration queueWarnThreshold,

		@NotNull @DefaultValue("60s") Duration summaryInterval) {
}
