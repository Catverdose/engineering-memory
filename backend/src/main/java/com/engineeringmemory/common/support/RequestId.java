package com.engineeringmemory.common.support;

import java.util.Optional;

import org.slf4j.MDC;

public final class RequestId {

	public static final String MDC_KEY = "request_id";

	public static final String HEADER = "X-Request-Id";

	private static final int MAX_LENGTH = 64;

	private RequestId() {
	}

	public static Optional<String> current() {
		return Optional.ofNullable(MDC.get(MDC_KEY)).filter(RequestId::isValid);
	}

	public static boolean isValid(String candidate) {
		if (candidate == null || candidate.isEmpty() || candidate.length() > MAX_LENGTH) {
			return false;
		}
		for (int i = 0; i < candidate.length(); i++) {
			char c = candidate.charAt(i);
			if (c < 0x21 || c > 0x7e) {
				return false;
			}
		}
		return true;
	}
}
