package sse

import (
	"net/http"
	"net/http/httputil"

	"personal-engineering-assistant/gateway/internal/backend"
)

func newReverseProxy(opts backend.Options) *httputil.ReverseProxy {
	return &httputil.ReverseProxy{
		Rewrite: func(pr *httputil.ProxyRequest) {
			backend.Rewrite(opts.Target, opts.TrustProxyHeaders)(pr)

			pr.Out.Header.Del("Accept-Encoding")
		},
		ErrorHandler: backend.ErrorHandler(opts.Logger),

		FlushInterval: -1,

		ModifyResponse: func(res *http.Response) error {
			res.Header.Set("X-Accel-Buffering", "no")

			res.Header.Set("Cache-Control", "no-cache, no-transform")
			return nil
		},
	}
}
