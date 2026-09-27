package com.engineeringmemory.knowledge.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "knowledge.seed")
public record KnowledgeSeedProperties(

		@DefaultValue("true") boolean enabled,

		@NotNull @DefaultValue("30s") Duration retryDelay) {
}
