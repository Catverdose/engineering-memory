package backend

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"net/http/httputil"
	"net/url"
	"time"
)

type Options struct {
	Target            *url.URL
	Timeout           time.Duration
	TrustProxyHeaders bool
	Logger            *slog.Logger
}

func Forward(opts Options) http.Handler {
	rp := &httputil.ReverseProxy{
		Rewrite:      Rewrite(opts.Target, opts.TrustProxyHeaders),
		ErrorHandler: ErrorHandler(opts.Logger),
	}

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), opts.Timeout)
		defer cancel()
		rp.ServeHTTP(w, r.WithContext(ctx))
	})
}

func Rewrite(target *url.URL, trustProxy bool) func(*httputil.ProxyRequest) {
	return func(pr *httputil.ProxyRequest) {
		pr.SetURL(target)
		pr.SetXForwarded()

		if trustProxy {
			if prior := pr.In.Header.Get("X-Forwarded-For"); prior != "" {
				current := pr.Out.Header.Get("X-Forwarded-For")
				if current != "" {
					pr.Out.Header.Set("X-Forwarded-For", prior+", "+current)
				} else {
					pr.Out.Header.Set("X-Forwarded-For", prior)
				}
			}
			if host := pr.In.Header.Get("X-Forwarded-Host"); host != "" {
				pr.Out.Header.Set("X-Forwarded-Host", host)
			}
			if proto := pr.In.Header.Get("X-Forwarded-Proto"); proto != "" {
				pr.Out.Header.Set("X-Forwarded-Proto", proto)
			}
		}

		pr.Out.Host = pr.In.Host
	}
}

func ErrorHandler(logger *slog.Logger) func(http.ResponseWriter, *http.Request, error) {
	return func(w http.ResponseWriter, r *http.Request, err error) {
		if errors.Is(err, context.Canceled) || errors.Is(r.Context().Err(), context.Canceled) {
			if logger != nil {
				logger.Debug("클라이언트 연결 종료", "path", r.URL.Path)
			}
			return
		}

		code, status, message := classify(err)
		if logger != nil {
			logger.Error("백엔드 전달 실패",
				"code", code, "path", r.URL.Path, "error", err.Error())
		}

		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.WriteHeader(status)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"code":      code,
			"message":   message,
			"retryable": true,
		})
	}
}

func classify(err error) (code string, status int, message string) {
	if errors.Is(err, context.DeadlineExceeded) {
		return "GATEWAY_TIMEOUT", http.StatusGatewayTimeout,
			"서버 응답이 지연되고 있습니다. 잠시 후 다시 시도해 주세요."
	}
	return "BACKEND_UNAVAILABLE", http.StatusBadGateway,
		"서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."
}
