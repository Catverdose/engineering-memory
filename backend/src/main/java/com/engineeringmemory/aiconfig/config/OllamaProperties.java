package com.engineeringmemory.aiconfig.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Validated
@ConfigurationProperties(prefix = "ollama")
public record OllamaProperties(

		@NotBlank String baseUrl,

		@NotBlank String embeddingModel,

		@NotBlank String generationModel,

		@Positive @DefaultValue("1024") int embeddingDimensions,

		@DecimalMin("0.0") @DecimalMax("2.0") @DefaultValue("0.2") double temperature,

		@Positive @Max(262144) @DefaultValue("32768") int numCtx,

		@Positive @Max(32768) @DefaultValue("1024") int numPredict,

		@NotNull @DefaultValue("5s") Duration connectTimeout,

		@NotNull @DefaultValue("180s") Duration readTimeout) {
}
