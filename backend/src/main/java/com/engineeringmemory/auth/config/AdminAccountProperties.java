package com.engineeringmemory.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

@Validated
@ConfigurationProperties(prefix = "admin.account")
public record AdminAccountProperties(

		@NotBlank @DefaultValue("admin") String username,

		@DefaultValue("") String password) {

	public boolean hasPassword() {
		return password != null && !password.isBlank();
	}

	public boolean isEncoded() {
		return hasPassword() && password.startsWith("{");
	}
}
