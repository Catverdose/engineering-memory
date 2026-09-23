package com.engineeringmemory.auth.service;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.engineeringmemory.auth.entity.UserAccount;

public final class AssistantPrincipal implements UserDetails, CredentialsContainer, Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	private final long ownerId;
	private final String username;
	private final boolean enabled;
	private final List<GrantedAuthority> authorities;

	private String passwordHash;

	private AssistantPrincipal(long ownerId, String username, String passwordHash,
			boolean enabled, List<GrantedAuthority> authorities) {
		this.ownerId = ownerId;
		this.username = username;
		this.passwordHash = passwordHash;
		this.enabled = enabled;
		this.authorities = List.copyOf(authorities);
	}

	public static AssistantPrincipal from(UserAccount account) {
		if (account.getId() == null) {
			throw new IllegalArgumentException("저장되지 않은 계정으로 인증 주체를 만들 수 없습니다.");
		}
		return new AssistantPrincipal(
				account.getId(),
				account.getUsername(),
				account.getPasswordHash(),
				account.isActive(),
				List.of(new SimpleGrantedAuthority("ROLE_" + account.getRole().name())));
	}

	public long ownerId() {
		return ownerId;
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return authorities;
	}

	@Override
	public String getPassword() {
		return passwordHash;
	}

	@Override
	public String getUsername() {
		return username;
	}

	@Override
	public boolean isAccountNonExpired() {
		return true;
	}

	@Override
	public boolean isAccountNonLocked() {
		return true;
	}

	@Override
	public boolean isCredentialsNonExpired() {
		return true;
	}

	@Override
	public boolean isEnabled() {
		return enabled;
	}

	@Override
	public void eraseCredentials() {
		passwordHash = null;
	}
}
