package com.engineeringmemory.auth.dto;

import java.util.List;

import org.springframework.security.core.Authentication;

import com.engineeringmemory.auth.service.AssistantPrincipal;

public record AuthenticatedUser(
		boolean authenticated,
		Long ownerId,
		String username,
		List<String> roles) {

	private static final AuthenticatedUser ANONYMOUS =
			new AuthenticatedUser(false, null, null, List.of());

	public AuthenticatedUser {
		roles = List.copyOf(roles);
	}

	public static AuthenticatedUser from(Authentication authentication) {
		boolean loggedIn = authentication != null
				&& authentication.isAuthenticated()
				&& authentication.getPrincipal() instanceof AssistantPrincipal;
		if (!loggedIn) {
			return ANONYMOUS;
		}

		AssistantPrincipal principal = (AssistantPrincipal) authentication.getPrincipal();
		List<String> roles = authentication.getAuthorities().stream()
				.map(authority -> authority.getAuthority())
				.filter(authority -> authority.startsWith("ROLE_"))
				.map(authority -> authority.substring("ROLE_".length()))
				.sorted()
				.toList();
		return new AuthenticatedUser(true, principal.ownerId(), principal.getUsername(), roles);
	}
}
