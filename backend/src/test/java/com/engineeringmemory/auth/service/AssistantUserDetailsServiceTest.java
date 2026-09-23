package com.engineeringmemory.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import com.engineeringmemory.auth.entity.UserAccount;
import com.engineeringmemory.auth.repository.UserAccountRepository;

class AssistantUserDetailsServiceTest {

	@Test
	@DisplayName("DB 계정을 ownerId가 포함된 principal로 읽는다")
	void loadUserByUsername_returnsDatabasePrincipal() throws Exception {
		UserAccountRepository repository = mock(UserAccountRepository.class);
		UserAccount account = UserAccount.initialOwner("owner", "{noop}secret");
		Field id = UserAccount.class.getDeclaredField("id");
		id.setAccessible(true);
		id.set(account, 17L);
		when(repository.findByUsername("owner")).thenReturn(Optional.of(account));

		AssistantPrincipal principal = (AssistantPrincipal)
				new AssistantUserDetailsService(repository).loadUserByUsername("owner");

		assertThat(principal.ownerId()).isEqualTo(17L);
		assertThat(principal.getUsername()).isEqualTo("owner");
	}

	@Test
	@DisplayName("없는 username은 존재 여부를 상세히 노출하지 않고 인증 실패로 처리한다")
	void loadUserByUsername_throwsWhenAccountDoesNotExist() {
		UserAccountRepository repository = mock(UserAccountRepository.class);
		when(repository.findByUsername("missing")).thenReturn(Optional.empty());

		assertThatThrownBy(() ->
				new AssistantUserDetailsService(repository).loadUserByUsername("missing"))
				.isInstanceOf(UsernameNotFoundException.class);
	}
}
