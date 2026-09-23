package com.engineeringmemory.llm.observability;

public record ServerTiming(long totalMs, long loadMs, long promptEvalMs, long evalMs) {

	public static ServerTiming ofNanos(Long total, Long load, Long promptEval, Long eval) {
		if (total == null) {
			return null;
		}
		return new ServerTiming(ms(total), ms(load), ms(promptEval), ms(eval));
	}

	private static long ms(Long nanos) {
		return nanos == null ? 0 : nanos / 1_000_000;
	}

	public long queueMs() {
		return Math.max(0, totalMs - loadMs - promptEvalMs - evalMs);
	}

	public long workMs() {
		return promptEvalMs + evalMs;
	}
}
