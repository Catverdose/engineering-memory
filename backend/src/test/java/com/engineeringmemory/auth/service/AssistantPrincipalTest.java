package com.engineeringmemory.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.engineeringmemory.auth.entity.UserAccount;

class AssistantPrincipalTest {

	@Test
	@DisplayName("DB 계정의 불변 PK와 역할을 principal로 옮긴다")
	void from_copiesOwnerIdentityAndRole() throws Exception {
		UserAccount account = UserAccount.initialOwner("owner", "{noop}secret");
		Field id = UserAccount.class.getDeclaredField("id");
		id.setAccessible(true);
		id.set(account, 9L);

		AssistantPrincipal principal = AssistantPrincipal.from(account);

		assertThat(principal.ownerId()).isEqualTo(9L);
		assertThat(principal.getUsername()).isEqualTo("owner");
		assertThat(principal.getAuthorities())
				.extracting(authority -> authority.getAuthority())
				.containsExactly("ROLE_ADMIN");
		assertThat(principal.isEnabled()).isTrue();
	}

	@Test
	@DisplayName("인증 후에는 세션 principal에서 비밀번호 해시를 지운다")
	void eraseCredentials_removesPasswordHash() throws Exception {
		UserAccount account = UserAccount.initialOwner("owner", "{noop}secret");
		Field id = UserAccount.class.getDeclaredField("id");
		id.setAccessible(true);
		id.set(account, 9L);
		AssistantPrincipal principal = AssistantPrincipal.from(account);

		principal.eraseCredentials();

		assertThat(principal.getPassword()).isNull();
		assertThat(principal.ownerId()).isEqualTo(9L);
	}
}
