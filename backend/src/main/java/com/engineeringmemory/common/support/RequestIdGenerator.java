package com.engineeringmemory.common.support;

import java.util.UUID;

import org.springframework.stereotype.Component;

public interface RequestIdGenerator {

	String newRequestId();

	@Component
	class Uuid implements RequestIdGenerator {
		@Override
		public String newRequestId() {
			return UUID.randomUUID().toString();
		}
	}
}
