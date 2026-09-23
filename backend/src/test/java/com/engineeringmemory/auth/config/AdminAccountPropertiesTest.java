package com.engineeringmemory.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AdminAccountPropertiesTest {

	private static AdminAccountProperties withPassword(String password) {
		return new AdminAccountProperties("admin", password);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "   ", "\t", "\n" })
	@DisplayName("비어 있거나 공백뿐인 비밀번호는 설정되지 않은 것으로 본다")
	void hasPassword_isFalse_whenPasswordIsBlank(String password) {
		assertThat(withPassword(password).hasPassword()).isFalse();
	}

	@Test
	@DisplayName("null 비밀번호도 설정되지 않은 것으로 본다")
	void hasPassword_isFalse_whenPasswordIsNull() {
		assertThat(withPassword(null).hasPassword()).isFalse();
	}

	@Test
	@DisplayName("값이 있으면 설정된 것으로 본다")
	void hasPassword_isTrue_whenPasswordIsPresent() {
		assertThat(withPassword("s3cret").hasPassword()).isTrue();
	}

	@Test
	@DisplayName("{bcrypt} 접두사가 붙은 값은 이미 인코딩된 것으로 본다")
	void isEncoded_isTrue_whenPasswordHasAlgorithmPrefix() {
		AdminAccountProperties properties =
				withPassword("{bcrypt}$2a$10$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUV");

		assertThat(properties.isEncoded()).isTrue();
	}

	@Test
	@DisplayName("평문은 인코딩되지 않은 것으로 본다")
	void isEncoded_isFalse_whenPasswordIsPlainText() {
		assertThat(withPassword("s3cret").isEncoded()).isFalse();
	}

	@Test
	@DisplayName("비어 있는 값은 인코딩된 것으로 보지 않는다")
	void isEncoded_isFalse_whenPasswordIsBlank() {
		assertThat(withPassword("").isEncoded()).isFalse();
	}
}
