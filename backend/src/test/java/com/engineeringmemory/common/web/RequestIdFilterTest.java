package com.engineeringmemory.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.engineeringmemory.common.support.RequestId;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class RequestIdFilterTest {

	private final List<String> generated = new ArrayList<>();
	private final RequestIdFilter filter = new RequestIdFilter(() -> {
		String id = "generated-" + (generated.size() + 1);
		generated.add(id);
		return id;
	});

	@AfterEach
	void clearMdc() {
		MDC.clear();
	}

	@Test
	@DisplayName("게이트웨이가 넘긴 식별자를 그대로 쓴다")
	void reusesIncomingHeader() throws Exception {
		Seen seen = run(request("0123456789abcdef"), new MockHttpServletResponse());

		assertThat(seen.mdc).isEqualTo("0123456789abcdef");
		assertThat(generated).as("들어온 값이 멀쩡하면 만들지 않는다").isEmpty();
	}

	@Test
	@DisplayName("헤더가 없으면 백엔드가 만든다")
	void generatesWhenHeaderMissing() throws Exception {
		Seen seen = run(new MockHttpServletRequest(), new MockHttpServletResponse());

		assertThat(seen.mdc).isEqualTo("generated-1");
	}

	@Test
	@DisplayName("로그에 실을 수 없는 값은 쓰지 않는다")
	void rejectsUnloggableValues() throws Exception {
		assertThat(run(request("abc\ndef"), new MockHttpServletResponse()).mdc).isEqualTo("generated-1");
		assertThat(run(request("x".repeat(65)), new MockHttpServletResponse()).mdc).isEqualTo("generated-2");
		assertThat(run(request(""), new MockHttpServletResponse()).mdc).isEqualTo("generated-3");
	}

	@Test
	@DisplayName("들어온 값을 잘라 쓰지 않는다")
	void doesNotTruncateIncomingValue() throws Exception {
		String tooLong = "y".repeat(100);
		Seen seen = run(request(tooLong), new MockHttpServletResponse());

		assertThat(seen.mdc).doesNotContain("y");
	}

	@Test
	@DisplayName("응답 헤더로 돌려준다")
	void echoesIdInResponse() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		run(request("trace-42"), response);

		assertThat(response.getHeader(RequestId.HEADER)).isEqualTo("trace-42");
	}

	@Test
	@DisplayName("요청이 끝나면 MDC 에 남기지 않는다")
	void clearsMdcAfterRequest() throws Exception {
		run(request("trace-42"), new MockHttpServletResponse());

		assertThat(MDC.get(RequestId.MDC_KEY)).isNull();
	}

	@Test
	@DisplayName("체인이 터져도 MDC 를 치운다")
	void clearsMdcEvenWhenChainThrows() {
		FilterChain boom = (req, res) -> {
			throw new IllegalStateException("boom");
		};
		try {
			filter.doFilter(request("trace-42"), new MockHttpServletResponse(), boom);
		} catch (Exception expected) {
		}
		assertThat(MDC.get(RequestId.MDC_KEY)).isNull();
	}

	private static MockHttpServletRequest request(String headerValue) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(RequestId.HEADER, headerValue);
		return request;
	}

	private record Seen(String mdc) {
	}

	private Seen run(HttpServletRequest request, HttpServletResponse response) throws Exception {
		String[] captured = new String[1];
		MockFilterChain chain = new MockFilterChain() {
			@Override
			public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
				captured[0] = MDC.get(RequestId.MDC_KEY);
			}
		};
		filter.doFilter(request, response, chain);
		return new Seen(captured[0]);
	}
}
