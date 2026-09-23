package com.engineeringmemory.auth.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.engineeringmemory.auth.entity.UserAccount;
import com.engineeringmemory.auth.service.AssistantPrincipal;

class AuthenticatedUserTest {

	@Test
	@DisplayName("인증 객체가 없으면 비로그인이다")
	void from_returnsAnonymous_whenAuthenticationIsNull() {
		AuthenticatedUser user = AuthenticatedUser.from(null);

		assertThat(user.authenticated()).isFalse();
		assertThat(user.ownerId()).isNull();
		assertThat(user.username()).isNull();
		assertThat(user.roles()).isEmpty();
	}

	@Test
	@DisplayName("익명 인증 객체는 로그인으로 보지 않는다")
	void from_returnsAnonymous_whenPrincipalIsAnonymousUser() {
		AnonymousAuthenticationToken anonymous = new AnonymousAuthenticationToken(
				"key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

		AuthenticatedUser user = AuthenticatedUser.from(anonymous);

		assertThat(user.authenticated()).isFalse();
		assertThat(user.ownerId()).isNull();
		assertThat(user.username()).isNull();
		assertThat(user.roles()).isEmpty();
	}

	@Test
	@DisplayName("DB principal로 인증된 사용자는 ownerId, 아이디, 역할을 함께 돌려준다")
	void from_returnsOwnerIdUsernameAndRoles_whenAuthenticated() throws Exception {
		AssistantPrincipal principal = principal(42L);
		UsernamePasswordAuthenticationToken authenticated =
				UsernamePasswordAuthenticationToken.authenticated(
						principal, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

		AuthenticatedUser user = AuthenticatedUser.from(authenticated);

		assertThat(user.authenticated()).isTrue();
		assertThat(user.ownerId()).isEqualTo(42L);
		assertThat(user.username()).isEqualTo("admin");
		assertThat(user.roles()).containsExactly("ADMIN");
	}

	@Test
	@DisplayName("문자열 principal은 인증 표시가 있어도 owner로 인정하지 않는다")
	void from_returnsAnonymous_whenPrincipalHasNoDatabaseOwnerId() {
		UsernamePasswordAuthenticationToken authenticated =
				UsernamePasswordAuthenticationToken.authenticated(
						"admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

		AuthenticatedUser user = AuthenticatedUser.from(authenticated);

		assertThat(user.authenticated()).isFalse();
		assertThat(user.ownerId()).isNull();
	}

	@Test
	@DisplayName("응답에는 비밀번호가 담기지 않는다")
	void authenticatedUser_hasNoPasswordField() {
		assertThat(AuthenticatedUser.class.getRecordComponents())
				.extracting(java.lang.reflect.RecordComponent::getName)
				.containsExactly("authenticated", "ownerId", "username", "roles");
	}

	private static AssistantPrincipal principal(long ownerId) throws Exception {
		UserAccount account = UserAccount.initialOwner("admin", "{noop}secret");
		Field id = UserAccount.class.getDeclaredField("id");
		id.setAccessible(true);
		id.set(account, ownerId);
		return AssistantPrincipal.from(account);
	}
}
