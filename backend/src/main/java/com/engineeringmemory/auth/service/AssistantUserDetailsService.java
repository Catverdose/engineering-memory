package com.engineeringmemory.auth.service;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.engineeringmemory.auth.repository.UserAccountRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AssistantUserDetailsService implements UserDetailsService {

	private final UserAccountRepository userAccountRepository;

	@Override
	@Transactional(readOnly = true)
	public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
		return userAccountRepository.findByUsername(username)
				.map(AssistantPrincipal::from)
				.orElseThrow(() -> new UsernameNotFoundException("계정을 찾을 수 없습니다."));
	}
}
