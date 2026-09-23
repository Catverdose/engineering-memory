package com.engineeringmemory.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.engineeringmemory.auth.config.AdminAccountProperties;
import com.engineeringmemory.auth.entity.UserAccount;
import com.engineeringmemory.auth.repository.UserAccountRepository;

class InitialOwnerBootstrapTest {

	@Test
	@DisplayName("prod에서는 비밀번호가 없으면 기존 계정 조회 전 기동을 거부한다")
	void run_rejectsMissingPasswordInProduction() {
		UserAccountRepository repository = mock(UserAccountRepository.class);
		Environment environment = mock(Environment.class);
		when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(true);
		InitialOwnerBootstrap bootstrap = new InitialOwnerBootstrap(
				repository,
				new AdminAccountProperties("admin", ""),
				mock(PasswordEncoder.class),
				environment);

		assertThatThrownBy(() -> bootstrap.run(mock(ApplicationArguments.class)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("ADMIN_PASSWORD");
		verifyNoInteractions(repository);
	}

	@Test
	@DisplayName("기존 계정이 있으면 설정값으로 비밀번호를 덮어쓰지 않는다")
	void run_doesNothingWhenAccountAlreadyExists() {
		UserAccountRepository repository = mock(UserAccountRepository.class);
		PasswordEncoder encoder = mock(PasswordEncoder.class);
		Environment environment = mock(Environment.class);
		when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(false);
		when(repository.count()).thenReturn(1L);
		InitialOwnerBootstrap bootstrap = new InitialOwnerBootstrap(
				repository,
				new AdminAccountProperties("admin", "changed-secret"),
				encoder,
				environment);

		bootstrap.run(mock(ApplicationArguments.class));

		verify(repository, never()).saveAndFlush(any());
		verifyNoInteractions(encoder);
	}

	@Test
	@DisplayName("빈 DB에는 설정된 비밀번호를 인코딩해 ACTIVE ADMIN owner를 만든다")
	void run_createsInitialOwnerFromConfiguredPassword() {
		UserAccountRepository repository = mock(UserAccountRepository.class);
		PasswordEncoder encoder = mock(PasswordEncoder.class);
		Environment environment = mock(Environment.class);
		when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(false);
		when(repository.count()).thenReturn(0L);
		when(encoder.encode("secret")).thenReturn("{bcrypt}hash");
		when(repository.saveAndFlush(any(UserAccount.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		InitialOwnerBootstrap bootstrap = new InitialOwnerBootstrap(
				repository,
				new AdminAccountProperties("owner", "secret"),
				encoder,
				environment);

		bootstrap.run(mock(ApplicationArguments.class));

		ArgumentCaptor<UserAccount> account = ArgumentCaptor.forClass(UserAccount.class);
		verify(repository).saveAndFlush(account.capture());
		assertThat(account.getValue().getUsername()).isEqualTo("owner");
		assertThat(account.getValue().getPasswordHash()).isEqualTo("{bcrypt}hash");
		assertThat(account.getValue().getRole()).isEqualTo(UserAccount.Role.ADMIN);
		assertThat(account.getValue().getStatus()).isEqualTo(UserAccount.Status.ACTIVE);
	}

	@Test
	@DisplayName("기본·local 환경의 빈 DB에서만 임의 비밀번호를 만들어 인코딩한다")
	void run_generatesPasswordOnlyForEmptyNonProductionDatabase() {
		UserAccountRepository repository = mock(UserAccountRepository.class);
		PasswordEncoder encoder = mock(PasswordEncoder.class);
		Environment environment = mock(Environment.class);
		when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(false);
		when(repository.count()).thenReturn(0L);
		when(encoder.encode(anyString())).thenReturn("{bcrypt}generated-hash");
		when(repository.saveAndFlush(any(UserAccount.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		InitialOwnerBootstrap bootstrap = new InitialOwnerBootstrap(
				repository,
				new AdminAccountProperties("owner", ""),
				encoder,
				environment);

		bootstrap.run(mock(ApplicationArguments.class));

		verify(encoder).encode(anyString());
		ArgumentCaptor<UserAccount> account = ArgumentCaptor.forClass(UserAccount.class);
		verify(repository).saveAndFlush(account.capture());
		assertThat(account.getValue().getPasswordHash()).isEqualTo("{bcrypt}generated-hash");
	}
}
