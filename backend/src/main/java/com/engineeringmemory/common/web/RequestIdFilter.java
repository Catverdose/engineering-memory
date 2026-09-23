package com.engineeringmemory.common.web;

import java.io.IOException;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.engineeringmemory.common.support.RequestId;
import com.engineeringmemory.common.support.RequestIdGenerator;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class RequestIdFilter extends OncePerRequestFilter {

	private final RequestIdGenerator requestIdGenerator;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {

		String incoming = request.getHeader(RequestId.HEADER);
		String requestId = RequestId.isValid(incoming) ? incoming : requestIdGenerator.newRequestId();

		MDC.put(RequestId.MDC_KEY, requestId);
		response.setHeader(RequestId.HEADER, requestId);
		try {
			filterChain.doFilter(request, response);
		} finally {
			MDC.remove(RequestId.MDC_KEY);
		}
	}
}
