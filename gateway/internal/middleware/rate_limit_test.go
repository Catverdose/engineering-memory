package middleware

import (
	"testing"
	"time"
)

func TestTokenBucketRejectsUntrackedClientAtCapacity(t *testing.T) {
	now := time.Unix(0, 0)
	limiter := newTokenBucket(3, time.Minute, 2)

	if !limiter.allow("client-a", now) || !limiter.allow("client-b", now) {
		t.Fatal("상한까지의 기존 클라이언트는 허용되어야 한다")
	}
	if limiter.allow("client-c", now) {
		t.Fatal("비어 있는 슬롯이 없는데 새 클라이언트가 허용됐다")
	}
	if got := limiter.size(); got != 2 {
		t.Fatalf("추적 클라이언트 수=%d, 상한 2를 넘거나 기존 항목이 사라졌다", got)
	}

	if !limiter.allow("client-a", now) {
		t.Fatal("기존 클라이언트까지 상한 때문에 거절됐다")
	}
}

func TestTokenBucketAdmitsNewClientAfterIdleEviction(t *testing.T) {
	now := time.Unix(0, 0)
	limiter := newTokenBucket(2, time.Minute, 1)
	limiter.allow("old-client", now)

	if !limiter.allow("new-client", now.Add(2*time.Minute)) {
		t.Fatal("완전히 충전될 만큼 쉰 버킷을 정리한 뒤 새 클라이언트를 허용하지 않았다")
	}
	if got := limiter.size(); got != 1 {
		t.Fatalf("idle 정리 뒤 추적 클라이언트 수=%d, want 1", got)
	}
}
