package com.engineeringmemory.common.support;

import java.util.ArrayList;
import java.util.List;

public record BatchOutcome<T>(
		int requested,
		List<T> succeeded,
		List<Failure<T>> failed,
		List<T> skipped,
		List<T> notAttempted,
		boolean stoppedEarly,
		String stopReason) {

	public record Failure<T>(T item, String errorCode, String message) {
	}

	public boolean isCompleteSuccess() {
		return failed.isEmpty() && notAttempted.isEmpty();
	}

	public static <T> Builder<T> builder(int requested) {
		return new Builder<>(requested);
	}

	public static final class Builder<T> {
		private final int requested;
		private final List<T> succeeded = new ArrayList<>();
		private final List<Failure<T>> failed = new ArrayList<>();
		private final List<T> skipped = new ArrayList<>();
		private final List<T> notAttempted = new ArrayList<>();
		private String stopReason;

		private Builder(int requested) {
			this.requested = requested;
		}

		public Builder<T> succeeded(T item) {
			succeeded.add(item);
			return this;
		}

		public Builder<T> failed(T item, String errorCode, String message) {
			failed.add(new Failure<>(item, errorCode, message));
			return this;
		}

		public Builder<T> skipped(T item) {
			skipped.add(item);
			return this;
		}

		public Builder<T> notAttempted(List<T> items) {
			notAttempted.addAll(items);
			return this;
		}

		public Builder<T> stoppedBecause(String reason) {
			this.stopReason = reason;
			return this;
		}

		public int failedCount() {
			return failed.size();
		}

		public BatchOutcome<T> build() {
			return new BatchOutcome<>(
					requested,
					List.copyOf(succeeded),
					List.copyOf(failed),
					List.copyOf(skipped),
					List.copyOf(notAttempted),
					stopReason != null,
					stopReason);
		}
	}
}
