package com.engineeringmemory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class ConfigYamlTest {

	private static final String BASE = "application.yml";
	private static final String LOCAL = "application-local.yml";
	private static final String PROD = "application-prod.yml";

	private static Map<String, Object> load(String name) {
		try {
			List<PropertySource<?>> sources =
					new YamlPropertySourceLoader().load(name, new ClassPathResource(name));
			Map<String, Object> flat = new LinkedHashMap<>();
			for (PropertySource<?> source : sources) {
				if (source instanceof EnumerablePropertySource<?> enumerable) {
					for (String key : enumerable.getPropertyNames()) {
						flat.put(key, enumerable.getProperty(key));
					}
				}
			}
			return flat;
		} catch (IOException e) {
			throw new AssertionError(name + " 을 읽을 수 없습니다.", e);
		}
	}

	private static String str(Map<String, Object> props, String key) {
		Object v = props.get(key);
		assertThat(v).as("%s 키가 없다", key).isNotNull();
		return String.valueOf(v);
	}

	@Test
	@DisplayName("설정 YAML 세 장이 모두 파싱된다")
	void allThreeProfilesParse() {
		assertThat(load(BASE)).isNotEmpty();
		assertThat(load(LOCAL)).isNotEmpty();
		assertThat(load(PROD)).isNotEmpty();
	}

	private static final java.util.Set<String> PROFILE_ONLY_KEYS = java.util.Set.of(
			"spring.jpa.properties.hibernate.format_sql",

			"server.servlet.session.cookie.secure",

			"server.forward-headers-strategy");

	@Test
	@DisplayName("프로파일의 모든 키는 base 에도 존재한다 (오타로 죽은 설정 방지)")
	void profileKeysAllExistInBase() {
		Map<String, Object> base = load(BASE);

		for (String name : List.of(LOCAL, PROD)) {
			var orphans = new TreeSet<String>();
			for (String key : load(name).keySet()) {
				if (!base.containsKey(key) && !PROFILE_ONLY_KEYS.contains(key)) {
					orphans.add(key);
				}
			}
			assertThat(orphans)
					.as("%s 에만 있는 키. base 에 없으면 덮을 대상이 없어 아무 일도 하지 않는다."
							+ " 의도한 것이면 PROFILE_ONLY_KEYS 에 이유와 함께 등록한다", name)
					.isEmpty();
		}
	}

	@Test
	@DisplayName("허용 목록에 쓸모없어진 항목이 남아 있지 않다")
	void allowListHasNoStaleEntries() {
		Map<String, Object> base = load(BASE);
		var profileKeys = new TreeSet<String>();
		profileKeys.addAll(load(LOCAL).keySet());
		profileKeys.addAll(load(PROD).keySet());

		for (String allowed : PROFILE_ONLY_KEYS) {
			assertThat(base).as("%s 는 이제 base 에 있다. 허용 목록에서 빼야 한다", allowed)
					.doesNotContainKey(allowed);
			assertThat(profileKeys).as("%s 는 어느 프로파일에도 없다. 허용 목록에서 빼야 한다", allowed)
					.contains(allowed);
		}
	}

	@Test
	@DisplayName("base 의 요청 제한은 운영 기준으로 조여져 있다")
	void baseRateLimitIsTheSafeOne() {
		Map<String, Object> base = load(BASE);

		assertThat(str(base, "rate-limit.chat.enabled")).isEqualTo("true");
		assertThat(str(base, "rate-limit.chat.capacity")).isEqualTo("10");
		assertThat(str(base, "rate-limit.chat.refill-period")).isEqualTo("6s");
		assertThat(str(base, "rate-limit.login.capacity")).isEqualTo("5");
		assertThat(str(base, "rate-limit.login.refill-period")).isEqualTo("60s");
	}

	@Test
	@DisplayName("base 의 로깅은 INFO 다. DEBUG 는 개발 프로파일에서만 켠다")
	void baseLoggingIsInfo() {
		assertThat(str(load(BASE), "logging.level.com.engineeringmemory")).isEqualTo("INFO");
	}

	@Test
	@DisplayName("임베딩 차원은 DB 의 vector(N) 과 같은 1024 다")
	void embeddingDimensionsMatchSchema() {
		assertThat(str(load(BASE), "ollama.embedding-dimensions")).isEqualTo("1024");
	}

	@Test
	@DisplayName("개인 문서 검색의 RAG 안전 상한이 설정돼 있다")
	void ragValuesSurvivedTheConversion() {
		Map<String, Object> base = load(BASE);

		assertThat(str(base, "ollama.embedding-model")).isEqualTo("bge-m3");
		assertThat(str(base, "ollama.generation-model")).isEqualTo("exaone3.5:7.8b");
		assertThat(str(base, "ollama.num-ctx")).isEqualTo("32768");
		assertThat(str(base, "ollama.num-predict")).isEqualTo("1024");
		assertThat(str(base, "rag.similarity-threshold")).isEqualTo("0.55");
		assertThat(str(base, "rag.top-k")).isEqualTo("5");
		assertThat(str(base, "rag.max-prompt-chars")).isEqualTo("10000");
		assertThat(str(base, "rag.max-chunks-per-document")).isEqualTo("3");

		long numCtx = Long.parseLong(str(base, "ollama.num-ctx"));
		long numPredict = Long.parseLong(str(base, "ollama.num-predict"));
		long maxPromptChars = Long.parseLong(str(base, "rag.max-prompt-chars"));
		assertThat(maxPromptChars * 3 + numPredict + 64)
				.as("UTF-8 최악 조건의 입력, 출력, 모델 제어 토큰이 num-ctx 안에 들어야 한다")
				.isLessThanOrEqualTo(numCtx);
	}

	@Test
	@DisplayName("스트림 타임아웃이 모델 읽기 타임아웃보다 길다")
	void streamTimeoutOutlivesModelTimeout() {
		Map<String, Object> base = load(BASE);

		long ollama = seconds(str(base, "ollama.read-timeout"));
		long stream = seconds(str(base, "chat.stream.timeout"));

		assertThat(stream)
				.as("chat.stream.timeout(%ds)은 ollama.read-timeout(%ds)보다 길어야 한다", stream, ollama)
				.isGreaterThan(ollama);
	}

	@Test
	@DisplayName("local 은 요청 제한을 느슨하게, 로깅을 DEBUG 로 덮는다")
	void localLoosensForDevelopment() {
		Map<String, Object> local = load(LOCAL);
		Map<String, Object> base = load(BASE);

		assertThat(str(local, "logging.level.com.engineeringmemory")).isEqualTo("DEBUG");

		long baseCapacity = Long.parseLong(str(base, "rate-limit.chat.capacity"));
		long localCapacity = Long.parseLong(str(local, "rate-limit.chat.capacity"));
		assertThat(localCapacity).isGreaterThan(baseCapacity);
	}

	@Test
	@DisplayName("local 은 요청 제한을 끄지 않는다")
	void localDoesNotDisableRateLimit() {
		assertThat(load(LOCAL)).doesNotContainKey("rate-limit.chat.enabled");
	}

	@Test
	@DisplayName("prod 는 프록시 헤더를 신뢰하고 secure 쿠키를 켠다")
	void prodHardensForHttps() {
		Map<String, Object> prod = load(PROD);

		assertThat(str(prod, "server.forward-headers-strategy")).isEqualTo("framework");
		assertThat(str(prod, "server.servlet.session.cookie.secure")).isEqualTo("true");
	}

	@Test
	@DisplayName("HTTP 에서도 동작하는 쿠키 방어는 base 에 있다")
	void cookieDefensesThatWorkOverHttpAreInBase() {
		Map<String, Object> base = load(BASE);

		assertThat(str(base, "server.servlet.session.cookie.http-only")).isEqualTo("true");
		assertThat(str(base, "server.servlet.session.cookie.same-site")).isEqualTo("lax");

		assertThat(base).doesNotContainKey("server.servlet.session.cookie.secure");
	}

	@Test
	@DisplayName("prod 는 요청 제한을 느슨하게 덮지 않는다")
	void prodNeverLoosensRateLimit() {
		Map<String, Object> prod = load(PROD);
		Map<String, Object> base = load(BASE);

		if (prod.containsKey("rate-limit.chat.capacity")) {
			long baseCapacity = Long.parseLong(str(base, "rate-limit.chat.capacity"));
			long prodCapacity = Long.parseLong(str(prod, "rate-limit.chat.capacity"));
			assertThat(prodCapacity).isLessThanOrEqualTo(baseCapacity);
		}
	}

	@Test
	@DisplayName("비밀값은 환경변수 자리표로만 적혀 있다")
	void secretsAreEnvPlaceholders() {
		Map<String, Object> base = load(BASE);

		assertThat(str(base, "admin.account.password")).isEqualTo("${ADMIN_PASSWORD:}");
		assertThat(str(base, "spring.datasource.password")).startsWith("${DB_PASSWORD:");
		assertThat(base).doesNotContainKey("kakao.local.rest-api-key");
	}

	private static long seconds(String duration) {
		assertThat(duration).as("기간은 초 단위(예: 180s)로 적는다").endsWith("s");
		return Long.parseLong(duration.substring(0, duration.length() - 1));
	}
}
