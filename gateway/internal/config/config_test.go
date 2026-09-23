package config

import (
	"testing"
	"time"
)

func TestLoad(t *testing.T) {
	t.Run("BACKEND_URL 이 없으면 기동하지 않는다", func(t *testing.T) {
		t.Setenv("GATEWAY_BACKEND_URL", "")
		if _, err := Load(); err == nil {
			t.Error("오류가 나야 한다")
		}
	})

	t.Run("기본값을 채운다", func(t *testing.T) {
		t.Setenv("GATEWAY_BACKEND_URL", "http://backend:8080")

		cfg, err := Load()
		if err != nil {
			t.Fatalf("Load: %v", err)
		}
		if !(cfg.ProxyTimeout <= cfg.SlowTimeout && cfg.SlowTimeout <= cfg.StreamTimeout) {
			t.Errorf("기본 타임아웃 순서가 어긋난다: proxy=%s slow=%s stream=%s",
				cfg.ProxyTimeout, cfg.SlowTimeout, cfg.StreamTimeout)
		}
		if cfg.SlowTimeout <= 180*time.Second {
			t.Errorf("SlowTimeout=%s, 백엔드의 ollama.read-timeout(180s)보다 길어야 한다", cfg.SlowTimeout)
		}
		if cfg.WebSocketTimeout < cfg.StreamTimeout {
			t.Errorf("WebSocketTimeout=%s, StreamTimeout=%s 보다 짧다",
				cfg.WebSocketTimeout, cfg.StreamTimeout)
		}
	})

	t.Run("SLOW 이 PROXY 보다 짧으면 거절한다", func(t *testing.T) {
		t.Setenv("GATEWAY_BACKEND_URL", "http://backend:8080")
		t.Setenv("GATEWAY_PROXY_TIMEOUT", "60s")
		t.Setenv("GATEWAY_SLOW_TIMEOUT", "10s")

		if _, err := Load(); err == nil {
			t.Error("역전된 타임아웃은 기동에서 막아야 한다")
		}
	})

	t.Run("STREAM 이 PROXY 보다 짧으면 거절한다", func(t *testing.T) {
		t.Setenv("GATEWAY_BACKEND_URL", "http://backend:8080")
		t.Setenv("GATEWAY_PROXY_TIMEOUT", "60s")
		t.Setenv("GATEWAY_STREAM_TIMEOUT", "30s")

		if _, err := Load(); err == nil {
			t.Error("역전된 타임아웃은 기동에서 막아야 한다")
		}
	})

	t.Run("WEBSOCKET 이 STREAM 보다 짧으면 거절한다", func(t *testing.T) {
		t.Setenv("GATEWAY_BACKEND_URL", "http://backend:8080")
		t.Setenv("GATEWAY_STREAM_TIMEOUT", "360s")
		t.Setenv("GATEWAY_WEBSOCKET_TIMEOUT", "60s")

		if _, err := Load(); err == nil {
			t.Error("역전된 타임아웃은 기동에서 막아야 한다")
		}
	})

	t.Run("읽을 수 없는 값은 기본값으로 덮지 않는다", func(t *testing.T) {
		t.Setenv("GATEWAY_BACKEND_URL", "http://backend:8080")
		t.Setenv("GATEWAY_PROXY_TIMEOUT", "삼십초")

		if _, err := Load(); err == nil {
			t.Error("오타 난 설정이 조용히 무시되면 안 된다")
		}
	})
}
