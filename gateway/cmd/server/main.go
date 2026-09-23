package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"personal-engineering-assistant/gateway/internal/config"
	"personal-engineering-assistant/gateway/internal/router"
)

func main() {
	if len(os.Args) > 1 && os.Args[1] == "healthcheck" {
		os.Exit(runHealthcheck())
	}

	logger := newLogger()

	cfg, err := config.Load()
	if err != nil {
		logger.Error("설정을 읽지 못했습니다", "error", err)
		os.Exit(1)
	}

	handler, err := router.New(cfg, logger)
	if err != nil {
		logger.Error("라우터를 만들지 못했습니다", "error", err)
		os.Exit(1)
	}

	server := &http.Server{
		Addr:    cfg.ListenAddr,
		Handler: handler,

		ReadHeaderTimeout: 10 * time.Second,

		IdleTimeout: 120 * time.Second,
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	serverErr := make(chan error, 1)
	go func() {
		logger.Info("게이트웨이 시작",
			"addr", cfg.ListenAddr,
			"backend", cfg.BackendURL,
			"rate_limit", cfg.RateLimitEnabled,
			"trust_proxy_headers", cfg.TrustProxyHeaders,
			"stream_timeout", cfg.StreamTimeout.String(),
			"websocket_timeout", cfg.WebSocketTimeout.String(),
		)
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			serverErr <- err
		}
	}()

	select {
	case err := <-serverErr:
		logger.Error("서버가 멈췄습니다", "error", err)
		os.Exit(1)

	case <-ctx.Done():
		logger.Info("종료 신호를 받았습니다. 진행 중인 요청을 기다립니다",
			"grace", cfg.ShutdownGrace.String())

		shutdownCtx, cancel := context.WithTimeout(context.Background(), cfg.ShutdownGrace)
		defer cancel()

		if err := server.Shutdown(shutdownCtx); err != nil {
			logger.Error("정상 종료에 실패했습니다", "error", err)
			os.Exit(1)
		}
		logger.Info("종료했습니다")
	}
}

func newLogger() *slog.Logger {
	level := slog.LevelInfo
	if os.Getenv("GATEWAY_LOG_LEVEL") == "debug" {
		level = slog.LevelDebug
	}
	return slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: level}))
}
