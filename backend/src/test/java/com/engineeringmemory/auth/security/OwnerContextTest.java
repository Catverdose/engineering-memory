package com.engineeringmemory.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import com.engineeringmemory.auth.entity.UserAccount;
import com.engineeringmemory.auth.service.AssistantPrincipal;
import com.engineeringmemory.common.exception.BusinessException;
import com.engineeringmemory.common.exception.ErrorCode;

class OwnerContextTest {

	@Test
	@DisplayName("ownerId는 인증된 DB principal의 PK에서만 얻는다")
	void requireOwnerId_returnsDatabasePrincipalId() throws Exception {
		AssistantPrincipal principal = principal(73L);
		var authentication = UsernamePasswordAuthenticationToken.authenticated(
				principal, null, principal.getAuthorities());

		assertThat(OwnerContext.requireOwnerId(authentication)).isEqualTo(73L);
	}

	@Test
	@DisplayName("인증이 없으면 UNAUTHORIZED로 거부한다")
	void requireOwnerId_rejectsMissingAuthentication() {
		assertThatThrownBy(() -> OwnerContext.requireOwnerId(null))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
	}

	@Test
	@DisplayName("요청에서 만들 수 있는 문자열 principal은 owner로 인정하지 않는다")
	void requireOwnerId_rejectsNonDatabasePrincipal() {
		var authentication = UsernamePasswordAuthenticationToken.authenticated(
				"73", null, List.of());

		assertThatThrownBy(() -> OwnerContext.requireOwnerId(authentication))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
	}

	private static AssistantPrincipal principal(long ownerId) throws Exception {
		UserAccount account = UserAccount.initialOwner("admin", "{noop}secret");
		Field id = UserAccount.class.getDeclaredField("id");
		id.setAccessible(true);
		id.set(account, ownerId);
		return AssistantPrincipal.from(account);
	}
}
