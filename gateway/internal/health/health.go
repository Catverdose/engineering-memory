package health

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"time"
)

func Live() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"status": "UP"})
	})
}

func Ready(backend *url.URL, healthPath string, timeout time.Duration) http.Handler {
	client := &http.Client{Timeout: timeout}
	probe := backend.JoinPath(healthPath).String()

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), timeout)
		defer cancel()

		req, err := http.NewRequestWithContext(ctx, http.MethodGet, probe, nil)
		if err != nil {
			writeJSON(w, http.StatusServiceUnavailable, map[string]any{
				"status": "DOWN", "backend": "UNKNOWN", "reason": err.Error(),
			})
			return
		}

		res, err := client.Do(req)
		if err != nil {
			writeJSON(w, http.StatusServiceUnavailable, map[string]any{
				"status": "DOWN", "backend": "UNREACHABLE", "reason": err.Error(),
			})
			return
		}
		defer res.Body.Close()

		if res.StatusCode < 200 || res.StatusCode >= 300 {
			writeJSON(w, http.StatusServiceUnavailable, map[string]any{
				"status": "DOWN", "backend": "NOT_READY", "backendStatus": res.StatusCode,
			})
			return
		}

		writeJSON(w, http.StatusOK, map[string]any{"status": "UP", "backend": "UP"})
	})
}

func writeJSON(w http.ResponseWriter, status int, body map[string]any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}
