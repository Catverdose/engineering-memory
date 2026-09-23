package com.engineeringmemory.auth.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import tools.jackson.databind.ObjectMapper;

@SpringJUnitConfig(classes = { SecurityConfig.class, SecurityConfigTest.TestConfig.class })
@WebAppConfiguration
class SecurityConfigTest {

	@Autowired
	private WebApplicationContext context;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.apply(springSecurity())
				.build();
	}

	@Test
	@DisplayName("login, me, health만 인증 없이 접근할 수 있다")
	void publicEndpoints_areNarrowlyScoped() throws Exception {
		mockMvc.perform(get("/api/auth/me"))
				.andExpect(status().isOk());
		mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/auth/login").with(csrf()))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/knowledge/documents"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/conversations"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/auth/logout").with(csrf()))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("인증된 chat POST도 CSRF 토큰 없이는 거부한다")
	void chatPost_requiresAuthenticationAndCsrf() throws Exception {
		mockMvc.perform(post("/api/chat/messages"))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/api/chat/messages").with(user("owner").roles("USER")))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/api/chat/messages")
						.with(user("owner").roles("USER"))
						.with(csrf()))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("admin 경로는 ADMIN 역할만 접근한다")
	void adminEndpoints_requireAdminRole() throws Exception {
		mockMvc.perform(get("/api/admin/check").with(user("owner").roles("USER")))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/admin/check").with(user("admin").roles("ADMIN")))
				.andExpect(status().isOk());
	}

	@Configuration(proxyBeanMethods = false)
	@EnableWebMvc
	@EnableWebSecurity
	static class TestConfig {

		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper();
		}

		@Bean
		UserDetailsService userDetailsService() {
			return username -> User.withUsername(username)
					.password("{noop}unused")
					.roles("USER")
					.build();
		}

		@Bean
		TestEndpoints testEndpoints() {
			return new TestEndpoints();
		}
	}

	@RestController
	static class TestEndpoints {

		@GetMapping({ "/api/auth/me", "/actuator/health", "/api/knowledge/documents",
				"/api/conversations", "/api/admin/check" })
		String getEndpoint() {
			return "ok";
		}

		@PostMapping({ "/api/auth/login", "/api/auth/logout", "/api/chat/messages" })
		String postEndpoint() {
			return "ok";
		}
	}
}
