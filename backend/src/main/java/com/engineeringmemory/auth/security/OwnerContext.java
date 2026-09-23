package com.engineeringmemory.auth.security;

import org.springframework.security.core.Authentication;

import com.engineeringmemory.auth.service.AssistantPrincipal;
import com.engineeringmemory.common.exception.BusinessException;
import com.engineeringmemory.common.exception.ErrorCode;

public final class OwnerContext {

	private OwnerContext() {
	}

	public static long requireOwnerId(Authentication authentication) {
		if (authentication == null
				|| !authentication.isAuthenticated()
				|| !(authentication.getPrincipal() instanceof AssistantPrincipal principal)
				|| !principal.isEnabled()) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		return principal.ownerId();
	}
}
