package com.engineeringmemory.llm.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.engineeringmemory.aiconfig.config.ModelObservabilityProperties;
import com.engineeringmemory.llm.observability.ModelCallStats.Role;
import com.engineeringmemory.llm.observability.ModelCallStats.Snapshot;

class ModelCallStatsTest {

	private ModelCallStats stats() {
		return new ModelCallStats(new ModelObservabilityProperties(
				Duration.ofMillis(500), Duration.ofSeconds(60)));
	}

	private static ServerTiming immediate(long workMs) {
		return new ServerTiming(workMs, 0, 0, workMs);
	}

	private static ServerTiming queued(long queueMs, long workMs) {
		return new ServerTiming(queueMs + workMs, 0, 0, workMs);
	}

	@Test
	@DisplayName("대기는 total 에서 load·prompt·eval 을 뺀 나머지다")
	void queueIsTotalMinusReportedWork() {
		assertThat(new ServerTiming(561, 0, 305, 242).queueMs()).isEqualTo(14);
		assertThat(new ServerTiming(1060, 1, 44, 451).queueMs()).isEqualTo(564);
		assertThat(new ServerTiming(1341, 1, 44, 227).queueMs()).isEqualTo(1069);
	}

	@Test
	@DisplayName("모델 적재는 대기와 따로 센다")
	void loadIsNotCountedAsQueue() {
		ServerTiming coldStart = new ServerTiming(41_000, 40_000, 100, 900);

		assertThat(coldStart.loadMs()).isEqualTo(40_000);
		assertThat(coldStart.queueMs()).isZero();
		assertThat(coldStart.workMs()).isEqualTo(1_000);
	}

	@Test
	@DisplayName("보고값이 어긋나도 음수 대기로 세지 않는다")
	void neverNegative() {
		assertThat(new ServerTiming(100, 0, 90, 90).queueMs()).isZero();
	}

	@Test
	@DisplayName("total 이 없으면 분해 자체를 포기한다")
	void withoutTotalThereIsNoDecomposition() {
		assertThat(ServerTiming.ofNanos(null, 1L, 1L, 1L)).isNull();
		assertThat(ServerTiming.ofNanos(1_000_000L, null, null, null))
				.isEqualTo(new ServerTiming(1, 0, 0, 0));
	}

	@Test
	@DisplayName("진행 중인 호출 수를 역할별로 센다")
	void tracksInFlightPerRole() {
		ModelCallStats stats = stats();
		ModelCallStats.Ticket first = stats.start(Role.GENERATION);
		ModelCallStats.Ticket second = stats.start(Role.GENERATION);

		assertThat(stats.inFlight(Role.GENERATION)).isEqualTo(2);
		assertThat(stats.inFlight(Role.EMBEDDING)).isZero();

		first.finish(immediate(10));
		second.abort();
		assertThat(stats.inFlight(Role.GENERATION)).isZero();
	}

	@Test
	@DisplayName("실패로 끝나도 진행 수가 새지 않는다")
	void abortReleasesInFlight() {
		ModelCallStats stats = stats();
		stats.start(Role.EMBEDDING).abort();

		assertThat(stats.inFlight(Role.EMBEDDING)).isZero();
	}

	@Test
	@DisplayName("두 번 끝내도 한 번만 센다")
	void closingTwiceIsHarmless() {
		ModelCallStats stats = stats();
		ModelCallStats.Ticket ticket = stats.start(Role.GENERATION);
		ticket.finish(immediate(10));
		ticket.abort();

		assertThat(stats.inFlight(Role.GENERATION)).isZero();
		assertThat(stats.snapshot(Role.GENERATION).calls()).isEqualTo(1);
	}

	@Test
	@DisplayName("서버가 시간을 알려주지 않으면 집계에서 뺀다")
	void skipsCallsWithoutServerTiming() {
		ModelCallStats stats = stats();
		stats.start(Role.GENERATION).finish(null);

		assertThat(stats.snapshot(Role.GENERATION).calls()).isZero();
		assertThat(stats.inFlight(Role.GENERATION)).as("그래도 진행 수는 되돌린다").isZero();
	}

	@Test
	@DisplayName("평균과 최댓값을 함께 남긴다")
	void recordsAverageAndMax() {
		ModelCallStats stats = stats();
		stats.start(Role.GENERATION).finish(queued(100, 50));
		stats.start(Role.GENERATION).finish(queued(300, 50));

		Snapshot snapshot = stats.snapshot(Role.GENERATION);
		assertThat(snapshot.calls()).isEqualTo(2);
		assertThat(snapshot.averageQueueMs()).isEqualTo(200);
		assertThat(snapshot.maxQueueMs()).isEqualTo(300);
	}

	@Test
	@DisplayName("임계값을 넘은 대기만 느린 것으로 센다")
	void countsOnlyQueuesOverThreshold() {
		ModelCallStats stats = stats();
		stats.start(Role.GENERATION).finish(queued(100, 50));
		stats.start(Role.GENERATION).finish(queued(900, 50));

		assertThat(stats.snapshot(Role.GENERATION).slowCalls()).isEqualTo(1);
	}

	@Test
	@DisplayName("대기가 짧아도 적재가 길면 느린 것으로 센다")
	void longLoadAlsoCountsAsSlow() {
		ModelCallStats stats = stats();
		stats.start(Role.GENERATION).finish(new ServerTiming(41_000, 40_000, 100, 900));

		Snapshot snapshot = stats.snapshot(Role.GENERATION);
		assertThat(snapshot.slowCalls()).isEqualTo(1);
		assertThat(snapshot.loadMsMax()).isEqualTo(40_000);
		assertThat(snapshot.callsWithLoad()).isEqualTo(1);
	}

	@Test
	@DisplayName("같은 적재를 함께 기다린 호출들을 더해서 부풀리지 않는다")
	void concurrentCallsSharingOneLoadAreNotSummed() {
		ModelCallStats stats = stats();
		for (int i = 0; i < 6; i++) {
			stats.start(Role.EMBEDDING).finish(new ServerTiming(2300, 2250, 0, 0));
		}

		Snapshot snapshot = stats.snapshot(Role.EMBEDDING);
		assertThat(snapshot.loadMsMax()).isEqualTo(2250);
		assertThat(snapshot.callsWithLoad()).isEqualTo(6);
	}

	@Test
	@DisplayName("요약을 남기면 누적값을 비운다")
	void summaryResetsAccumulators() {
		ModelCallStats stats = stats();
		stats.start(Role.GENERATION).finish(queued(900, 50));

		assertThat(stats.snapshot(Role.GENERATION).calls()).isEqualTo(1);
		stats.logSummary();

		Snapshot after = stats.snapshot(Role.GENERATION);
		assertThat(after.calls()).isZero();
		assertThat(after.maxQueueMs()).isZero();
		assertThat(after.loadMsMax()).isZero();
		assertThat(after.callsWithLoad()).isZero();
	}
}
