package com.engineeringmemory.traffic.filter;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.engineeringmemory.common.exception.ErrorCode;
import com.engineeringmemory.common.exception.ErrorResponse;
import com.engineeringmemory.traffic.limiter.ChatRateLimiter;
import com.engineeringmemory.traffic.limiter.LoginRateLimiter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {

	private static final String CHAT_PATH_PREFIX = "/api/chat/";
	private static final String LOGIN_PATH = "/api/auth/login";

	private final ChatRateLimiter rateLimiter;
	private final LoginRateLimiter loginRateLimiter;
	private final ObjectMapper objectMapper;

	public RateLimitFilter(ChatRateLimiter rateLimiter, LoginRateLimiter loginRateLimiter,
			ObjectMapper objectMapper) {
		this.rateLimiter = rateLimiter;
		this.loginRateLimiter = loginRateLimiter;
		this.objectMapper = objectMapper;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		boolean chat = rateLimiter.enabled()
				&& request.getRequestURI().startsWith(CHAT_PATH_PREFIX);
		boolean login = loginRateLimiter.enabled()
				&& LOGIN_PATH.equals(request.getRequestURI())
				&& "POST".equalsIgnoreCase(request.getMethod());
		return !chat && !login;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {

		String clientKey = request.getRemoteAddr();
		boolean login = LOGIN_PATH.equals(request.getRequestURI());

		if (login ? loginRateLimiter.tryAcquire(clientKey) : rateLimiter.tryAcquire(clientKey)) {
			filterChain.doFilter(request, response);
			return;
		}

		if (login) {
			loginRateLimiter.logRejected(clientKey);
			writeTooManyRequests(response, loginRateLimiter.retryAfterSeconds());
		} else {
			rateLimiter.logRejected("http", clientKey);
			writeTooManyRequests(response, rateLimiter.retryAfterSeconds());
		}
	}

	private void writeTooManyRequests(HttpServletResponse response, long retryAfterSeconds) throws IOException {
		response.setStatus(ErrorCode.TOO_MANY_REQUESTS.getStatus().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
		response.getWriter().write(
				objectMapper.writeValueAsString(ErrorResponse.of(ErrorCode.TOO_MANY_REQUESTS)));
	}
}
