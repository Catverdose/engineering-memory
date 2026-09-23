package sse

import (
	"context"
	"net/http"

	"personal-engineering-assistant/gateway/internal/backend"
)

func New(opts backend.Options) http.Handler {
	rp := newReverseProxy(opts)

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), opts.Timeout)
		defer cancel()
		rp.ServeHTTP(w, r.WithContext(ctx))
	})
}
