package com.engineeringmemory.common.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.engineeringmemory.aiconfig.config.OllamaProperties;

@Configuration
public class RestClientConfig {

	@Bean
	RestClient ollamaRestClient(OllamaProperties properties) {
		return RestClient.builder()
				.baseUrl(properties.baseUrl())
				.requestFactory(requestFactory(properties.connectTimeout(), properties.readTimeout()))
				.build();
	}

	private static ClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(connectTimeout);
		factory.setReadTimeout(readTimeout);
		return factory;
	}
}
