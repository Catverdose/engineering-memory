package websocket

import (
	"context"
	"net/http"
	"net/http/httputil"

	"personal-engineering-assistant/gateway/internal/backend"
)

func New(opts backend.Options) http.Handler {
	rp := &httputil.ReverseProxy{
		Rewrite:      backend.Rewrite(opts.Target, opts.TrustProxyHeaders),
		ErrorHandler: backend.ErrorHandler(opts.Logger),
	}

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), opts.Timeout)
		defer cancel()
		rp.ServeHTTP(w, r.WithContext(ctx))
	})
}
