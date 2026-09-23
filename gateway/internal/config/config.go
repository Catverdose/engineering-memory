package config

import (
	"fmt"
	"os"
	"strconv"
	"time"
)

type Config struct {
	ListenAddr string

	BackendURL string

	BackendHealthPath string

	ProxyTimeout time.Duration

	SlowTimeout time.Duration

	StreamTimeout time.Duration

	WebSocketTimeout time.Duration

	ShutdownGrace time.Duration

	RateLimitEnabled bool

	RateLimitBurst int

	RateLimitRefill time.Duration

	TrustProxyHeaders bool
}

func Load() (Config, error) {
	cfg := Config{
		ListenAddr:        env("GATEWAY_LISTEN_ADDR", ":8000"),
		BackendURL:        os.Getenv("GATEWAY_BACKEND_URL"),
		BackendHealthPath: env("GATEWAY_BACKEND_HEALTH_PATH", "/actuator/health"),
		TrustProxyHeaders: os.Getenv("GATEWAY_TRUST_PROXY_HEADERS") == "true",
	}

	if cfg.BackendURL == "" {
		return Config{}, fmt.Errorf("GATEWAY_BACKEND_URL 이 필요합니다 (예: http://backend:8080)")
	}

	var err error
	if cfg.ProxyTimeout, err = envDuration("GATEWAY_PROXY_TIMEOUT", 30*time.Second); err != nil {
		return Config{}, err
	}
	if cfg.SlowTimeout, err = envDuration("GATEWAY_SLOW_TIMEOUT", 240*time.Second); err != nil {
		return Config{}, err
	}
	if cfg.StreamTimeout, err = envDuration("GATEWAY_STREAM_TIMEOUT", 360*time.Second); err != nil {
		return Config{}, err
	}
	if cfg.WebSocketTimeout, err = envDuration("GATEWAY_WEBSOCKET_TIMEOUT", 30*time.Minute); err != nil {
		return Config{}, err
	}
	if cfg.ShutdownGrace, err = envDuration("GATEWAY_SHUTDOWN_GRACE", 30*time.Second); err != nil {
		return Config{}, err
	}
	if cfg.RateLimitRefill, err = envDuration("GATEWAY_RATE_LIMIT_REFILL", 2*time.Second); err != nil {
		return Config{}, err
	}
	if cfg.RateLimitBurst, err = envInt("GATEWAY_RATE_LIMIT_BURST", 60); err != nil {
		return Config{}, err
	}

	cfg.RateLimitEnabled = env("GATEWAY_RATE_LIMIT_ENABLED", "true") == "true"

	if cfg.RateLimitBurst < 1 {
		return Config{}, fmt.Errorf("GATEWAY_RATE_LIMIT_BURST 는 1 이상이어야 합니다: %d", cfg.RateLimitBurst)
	}
	if cfg.SlowTimeout < cfg.ProxyTimeout {
		return Config{}, fmt.Errorf(
			"GATEWAY_SLOW_TIMEOUT(%s)은 GATEWAY_PROXY_TIMEOUT(%s)보다 짧을 수 없습니다",
			cfg.SlowTimeout, cfg.ProxyTimeout)
	}
	if cfg.StreamTimeout <= cfg.ProxyTimeout {
		return Config{}, fmt.Errorf(
			"GATEWAY_STREAM_TIMEOUT(%s)은 GATEWAY_PROXY_TIMEOUT(%s)보다 길어야 합니다",
			cfg.StreamTimeout, cfg.ProxyTimeout)
	}

	if cfg.WebSocketTimeout < cfg.StreamTimeout {
		return Config{}, fmt.Errorf(
			"GATEWAY_WEBSOCKET_TIMEOUT(%s)은 GATEWAY_STREAM_TIMEOUT(%s)보다 짧을 수 없습니다. "+
				"소켓 하나는 최소한 답변 한 번은 끝까지 받아야 합니다",
			cfg.WebSocketTimeout, cfg.StreamTimeout)
	}

	return cfg, nil
}

func env(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func envDuration(key string, fallback time.Duration) (time.Duration, error) {
	raw := os.Getenv(key)
	if raw == "" {
		return fallback, nil
	}
	d, err := time.ParseDuration(raw)
	if err != nil {
		return 0, fmt.Errorf("%s 값을 읽을 수 없습니다(%q): %w", key, raw, err)
	}
	if d <= 0 {
		return 0, fmt.Errorf("%s 는 0보다 커야 합니다: %s", key, d)
	}
	return d, nil
}

func envInt(key string, fallback int) (int, error) {
	raw := os.Getenv(key)
	if raw == "" {
		return fallback, nil
	}
	n, err := strconv.Atoi(raw)
	if err != nil {
		return 0, fmt.Errorf("%s 값을 읽을 수 없습니다(%q): %w", key, raw, err)
	}
	return n, nil
}
