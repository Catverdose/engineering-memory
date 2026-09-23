package com.engineeringmemory.streaming.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "chat.stream")
public record StreamProperties(

		@NotNull @DefaultValue("300s") Duration timeout) {
}
