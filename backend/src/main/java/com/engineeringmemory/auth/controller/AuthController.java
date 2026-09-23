package com.engineeringmemory.auth.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.engineeringmemory.auth.dto.AuthenticatedUser;
import com.engineeringmemory.auth.dto.LoginRequest;
import com.engineeringmemory.common.exception.BusinessException;
import com.engineeringmemory.common.exception.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AuthenticationManager authenticationManager;
	private final SecurityContextRepository securityContextRepository;

	@PostMapping("/login")
	public AuthenticatedUser login(
			@Valid @RequestBody LoginRequest loginRequest,
			HttpServletRequest request,
			HttpServletResponse response) {

		Authentication authentication;
		try {
			authentication = authenticationManager.authenticate(
					UsernamePasswordAuthenticationToken.unauthenticated(
							loginRequest.username(), loginRequest.password()));
		} catch (AuthenticationException e) {
			log.warn("로그인 실패: usernameHash={}", shortHash(loginRequest.username()));
			throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
		}

		HttpSession existing = request.getSession(false);
		if (existing != null) {
			existing.invalidate();
		}
		request.getSession(true);

		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		SecurityContextHolder.setContext(context);
		securityContextRepository.saveContext(context, request, response);

		log.info("로그인 성공: username={}", authentication.getName());
		return AuthenticatedUser.from(authentication);
	}

	private static String shortHash(String value) {
		try {
			String hex = HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
			return hex.substring(0, 12);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("JVM이 SHA-256을 지원하지 않습니다.", e);
		}
	}

	@PostMapping("/logout")
	public void logout(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session != null) {
			session.invalidate();
		}
		SecurityContextHolder.clearContext();
	}

	@GetMapping("/me")
	public AuthenticatedUser me(Authentication authentication) {
		return AuthenticatedUser.from(authentication);
	}

}
