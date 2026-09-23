package com.engineeringmemory.llm.observability;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.engineeringmemory.aiconfig.config.ModelObservabilityProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ModelCallStats {

	public enum Role {
		EMBEDDING("임베딩"),
		GENERATION("생성");

		private final String label;

		Role(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	private final ModelObservabilityProperties properties;

	private final Map<Role, RoleStats> byRole = new EnumMap<>(Map.of(
			Role.EMBEDDING, new RoleStats(),
			Role.GENERATION, new RoleStats()));

	public Ticket start(Role role) {
		RoleStats stats = byRole.get(role);
		int inFlight = stats.inFlight.incrementAndGet();
		return new Ticket(role, stats, inFlight);
	}

	public int inFlight(Role role) {
		return byRole.get(role).inFlight.get();
	}

	public Snapshot snapshot(Role role) {
		RoleStats s = byRole.get(role);
		long calls = s.calls.get();
		return new Snapshot(
				role,
				s.inFlight.get(),
				calls,
				calls == 0 ? 0 : s.queueMsTotal.get() / calls,
				s.queueMsMax.get(),
				s.loadMsMax.get(),
				s.callsWithLoad.get(),
				s.slowCalls.get());
	}

	@Scheduled(fixedDelayString = "${llm.observability.summary-interval:60s}")
	void logSummary() {
		for (Role role : Role.values()) {
			RoleStats stats = byRole.get(role);
			long calls = stats.calls.getAndSet(0);
			if (calls == 0) {
				continue;
			}
			long queueTotal = stats.queueMsTotal.getAndSet(0);
			long queueMax = stats.queueMsMax.getAndSet(0);
			long loadMax = stats.loadMsMax.getAndSet(0);
			long withLoad = stats.callsWithLoad.getAndSet(0);
			long workTotal = stats.workMsTotal.getAndSet(0);
			long slow = stats.slowCalls.getAndSet(0);

			log.info("모델 호출 요약: role={}, 건수={}, 평균대기={}ms, 최대대기={}ms, "
					+ "평균작업={}ms, 적재={}건/최대 {}ms, 느린호출={}건, 진행중={}",
					role.label(), calls, queueTotal / calls, queueMax,
					workTotal / calls, withLoad, loadMax, slow, stats.inFlight.get());
		}
	}

	public final class Ticket {

		private final Role role;
		private final RoleStats stats;
		private final int inFlightAtStart;
		private boolean closed;

		private Ticket(Role role, RoleStats stats, int inFlightAtStart) {
			this.role = role;
			this.stats = stats;
			this.inFlightAtStart = inFlightAtStart;
		}

		public void finish(ServerTiming timing) {
			if (closed) {
				return;
			}
			closed = true;
			stats.inFlight.decrementAndGet();

			if (timing == null) {
				return;
			}
			long queueMs = timing.queueMs();

			stats.calls.incrementAndGet();
			stats.queueMsTotal.addAndGet(queueMs);
			stats.queueMsMax.accumulateAndGet(queueMs, Math::max);
			if (timing.loadMs() > 0) {
				stats.loadMsMax.accumulateAndGet(timing.loadMs(), Math::max);
				stats.callsWithLoad.incrementAndGet();
			}
			stats.workMsTotal.addAndGet(timing.workMs());

			long threshold = properties.queueWarnThreshold().toMillis();
			if (queueMs >= threshold || timing.loadMs() >= threshold) {
				stats.slowCalls.incrementAndGet();
				log.warn("모델 호출이 일하기 전에 지연됐습니다: role={}, 대기={}ms, 적재={}ms, "
						+ "작업={}ms, 시작시점 동시건수={}",
						role.label(), queueMs, timing.loadMs(), timing.workMs(), inFlightAtStart);
			}
		}

		public void abort() {
			if (closed) {
				return;
			}
			closed = true;
			stats.inFlight.decrementAndGet();
		}
	}

	public record Snapshot(Role role, int inFlight, long calls,
			long averageQueueMs, long maxQueueMs, long loadMsMax, long callsWithLoad, long slowCalls) {
	}

	private static final class RoleStats {
		private final AtomicInteger inFlight = new AtomicInteger();
		private final AtomicLong calls = new AtomicLong();
		private final AtomicLong queueMsTotal = new AtomicLong();
		private final AtomicLong queueMsMax = new AtomicLong();
		private final AtomicLong loadMsMax = new AtomicLong();
		private final AtomicLong callsWithLoad = new AtomicLong();
		private final AtomicLong workMsTotal = new AtomicLong();
		private final AtomicLong slowCalls = new AtomicLong();
	}
}
