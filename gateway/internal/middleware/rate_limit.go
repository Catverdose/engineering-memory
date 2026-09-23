package middleware

import (
	"encoding/json"
	"hash/fnv"
	"log/slog"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

type RateLimitOptions struct {
	Burst int

	Refill time.Duration

	MaxClients int

	TrustProxyHeaders bool

	Logger *slog.Logger
}

func RateLimit(opts RateLimitOptions) func(http.Handler) http.Handler {
	limiter := newTokenBucket(opts.Burst, opts.Refill, opts.MaxClients)

	retryAfter := int(opts.Refill.Seconds())
	if retryAfter < 1 {
		retryAfter = 1
	}

	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			client := ClientIP(r, opts.TrustProxyHeaders)

			if limiter.allow(client, time.Now()) {
				next.ServeHTTP(w, r)
				return
			}

			if opts.Logger != nil {
				opts.Logger.Warn("요청 제한 초과",
					"request_id", RequestIDFrom(r.Context()),
					"client_hash", HashClient(client),
					"tracked_clients", limiter.size(),
					"path", r.URL.Path,
				)
			}

			w.Header().Set("Content-Type", "application/json; charset=utf-8")
			w.Header().Set("Retry-After", strconv.Itoa(retryAfter))
			w.WriteHeader(http.StatusTooManyRequests)
			_ = json.NewEncoder(w).Encode(map[string]any{
				"code":      "TOO_MANY_REQUESTS",
				"message":   "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.",
				"retryable": true,
			})
		})
	}
}

func ClientIP(r *http.Request, trustProxy bool) string {
	if trustProxy {
		if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
			if first, _, ok := strings.Cut(xff, ","); ok {
				return strings.TrimSpace(first)
			}
			return strings.TrimSpace(xff)
		}
		if real := r.Header.Get("X-Real-Ip"); real != "" {
			return strings.TrimSpace(real)
		}
	}

	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func HashClient(client string) string {
	h := fnv.New64a()
	_, _ = h.Write([]byte(client))
	return strconv.FormatUint(h.Sum64(), 16)
}

type tokenBucket struct {
	burst      int
	refill     time.Duration
	maxClients int

	idleUntilFull time.Duration

	mu      sync.Mutex
	buckets map[string]*bucket
}

type bucket struct {
	tokens     float64
	lastRefill time.Time
}

func newTokenBucket(burst int, refill time.Duration, maxClients int) *tokenBucket {
	if burst < 1 {
		burst = 1
	}
	if refill <= 0 {
		refill = time.Second
	}
	if maxClients < 1 {
		maxClients = 10000
	}
	return &tokenBucket{
		burst:         burst,
		refill:        refill,
		maxClients:    maxClients,
		idleUntilFull: refill * time.Duration(burst),
		buckets:       make(map[string]*bucket),
	}
}

func (t *tokenBucket) allow(client string, now time.Time) bool {
	t.mu.Lock()
	defer t.mu.Unlock()

	if len(t.buckets) >= t.maxClients {
		t.evictIdle(now)
	}

	b, ok := t.buckets[client]
	if !ok {
		if len(t.buckets) >= t.maxClients {
			return false
		}
		t.buckets[client] = &bucket{tokens: float64(t.burst) - 1, lastRefill: now}
		return true
	}

	elapsed := now.Sub(b.lastRefill)
	if elapsed < 0 {
		elapsed = 0
	}

	tokens := b.tokens + float64(elapsed)/float64(t.refill)
	if tokens > float64(t.burst) {
		tokens = float64(t.burst)
	}

	b.lastRefill = now

	if tokens >= 1 {
		b.tokens = tokens - 1
		return true
	}
	b.tokens = tokens
	return false
}

func (t *tokenBucket) evictIdle(now time.Time) {
	for k, b := range t.buckets {
		if now.Sub(b.lastRefill) >= t.idleUntilFull {
			delete(t.buckets, k)
		}
	}
}

func (t *tokenBucket) size() int {
	t.mu.Lock()
	defer t.mu.Unlock()
	return len(t.buckets)
}
