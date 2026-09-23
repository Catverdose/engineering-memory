package com.engineeringmemory.auth.service;

import java.util.UUID;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.engineeringmemory.auth.config.AdminAccountProperties;
import com.engineeringmemory.auth.entity.UserAccount;
import com.engineeringmemory.auth.repository.UserAccountRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class InitialOwnerBootstrap implements ApplicationRunner {

	private final UserAccountRepository userAccountRepository;
	private final AdminAccountProperties properties;
	private final PasswordEncoder passwordEncoder;
	private final Environment environment;

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		boolean production = environment.acceptsProfiles(Profiles.of("prod"));
		if (production && !properties.hasPassword()) {
			throw new IllegalStateException(
					"prod 프로파일에서는 최초 owner 비밀번호를 ADMIN_PASSWORD로 반드시 설정해야 합니다.");
		}

		if (userAccountRepository.count() > 0) {
			log.info("기존 사용자 계정이 있어 최초 owner 생성을 건너뜁니다.");
			return;
		}

		String generatedPassword = null;
		String configuredPassword = properties.password();
		if (!properties.hasPassword()) {
			generatedPassword = UUID.randomUUID().toString();
			configuredPassword = generatedPassword;
		}

		String passwordHash = properties.isEncoded()
				? configuredPassword
				: passwordEncoder.encode(configuredPassword);
		UserAccount owner = userAccountRepository.saveAndFlush(
				UserAccount.initialOwner(properties.username(), passwordHash));

		if (generatedPassword != null) {
			log.warn("""

					========================================================
					 최초 owner 계정을 생성했습니다. 개발용 임의 비밀번호입니다.
					 ownerId : {}
					 username: {}
					 password: {}
					 이 비밀번호는 다시 출력되지 않습니다.
					========================================================
					""", owner.getId(), owner.getUsername(), generatedPassword);
		} else {
			log.info("최초 owner 계정을 생성했습니다: ownerId={}, username={}",
					owner.getId(), owner.getUsername());
		}
	}
}
