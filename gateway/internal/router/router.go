package router

import (
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"time"

	"personal-engineering-assistant/gateway/internal/backend"
	"personal-engineering-assistant/gateway/internal/config"
	"personal-engineering-assistant/gateway/internal/health"
	"personal-engineering-assistant/gateway/internal/middleware"
	"personal-engineering-assistant/gateway/internal/sse"
	"personal-engineering-assistant/gateway/internal/websocket"
)

const readyProbeTimeout = 3 * time.Second

func New(cfg config.Config, logger *slog.Logger) (http.Handler, error) {
	target, err := url.Parse(cfg.BackendURL)
	if err != nil {
		return nil, fmt.Errorf("GATEWAY_BACKEND_URL 을 URL 로 읽을 수 없습니다(%q): %w", cfg.BackendURL, err)
	}
	if target.Scheme == "" || target.Host == "" {
		return nil, fmt.Errorf("GATEWAY_BACKEND_URL 에 스킴과 호스트가 있어야 합니다: %q", cfg.BackendURL)
	}

	forward := backend.Forward(backend.Options{
		Target:            target,
		Timeout:           cfg.ProxyTimeout,
		TrustProxyHeaders: cfg.TrustProxyHeaders,
		Logger:            logger,
	})

	slow := backend.Forward(backend.Options{
		Target:            target,
		Timeout:           cfg.SlowTimeout,
		TrustProxyHeaders: cfg.TrustProxyHeaders,
		Logger:            logger,
	})

	stream := sse.New(backend.Options{
		Target:            target,
		Timeout:           cfg.StreamTimeout,
		TrustProxyHeaders: cfg.TrustProxyHeaders,
		Logger:            logger,
	})

	ws := websocket.New(backend.Options{
		Target:            target,
		Timeout:           cfg.WebSocketTimeout,
		TrustProxyHeaders: cfg.TrustProxyHeaders,
		Logger:            logger,
	})

	limit := passthrough
	if cfg.RateLimitEnabled {
		limit = middleware.RateLimit(middleware.RateLimitOptions{
			Burst:             cfg.RateLimitBurst,
			Refill:            cfg.RateLimitRefill,
			MaxClients:        10000,
			TrustProxyHeaders: cfg.TrustProxyHeaders,
			Logger:            logger,
		})
	}

	mux := http.NewServeMux()

	mux.Handle("GET /healthz", health.Live())
	mux.Handle("GET /readyz", health.Ready(target, cfg.BackendHealthPath, readyProbeTimeout))

	mux.Handle("POST /api/chat/messages/stream", limit(stream))

	mux.Handle("POST /api/chat/messages", limit(slow))

	mux.Handle("GET /api/chat/ws", limit(ws))
	mux.Handle("/api/chat/", limit(forward))

	mux.Handle("POST /api/knowledge/documents", slow)
	mux.Handle("POST /api/knowledge/documents/{id}/reindex", slow)
	mux.Handle("/api/", forward)

	mux.Handle("/", notFound())

	var handler http.Handler = mux
	handler = middleware.Logging(logger, cfg.TrustProxyHeaders)(handler)
	handler = middleware.RequestID(handler)

	return handler, nil
}

func passthrough(next http.Handler) http.Handler { return next }

func notFound() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.WriteHeader(http.StatusNotFound)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"code":      "NOT_FOUND",
			"message":   "요청한 경로를 찾을 수 없습니다.",
			"retryable": false,
		})
	})
}
