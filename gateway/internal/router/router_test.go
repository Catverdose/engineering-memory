package router_test

import (
	"bufio"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"personal-engineering-assistant/gateway/internal/config"
	"personal-engineering-assistant/gateway/internal/router"
)

func quietLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, &slog.HandlerOptions{Level: slog.LevelError}))
}

func baseConfig(backendURL string) config.Config {
	return config.Config{
		ListenAddr:       ":0",
		BackendURL:       backendURL,
		ProxyTimeout:     5 * time.Second,
		SlowTimeout:      30 * time.Second,
		StreamTimeout:    10 * time.Second,
		WebSocketTimeout: 30 * time.Second,
		ShutdownGrace:    time.Second,
		RateLimitRefill:  time.Minute,
		RateLimitBurst:   2,
	}
}

func newGateway(t *testing.T, cfg config.Config) *httptest.Server {
	t.Helper()
	h, err := router.New(cfg, quietLogger())
	if err != nil {
		t.Fatalf("router.New: %v", err)
	}
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)
	return srv
}

func TestStream_DeliversFirstChunkBeforeBackendFinishes(t *testing.T) {
	release := make(chan struct{})

	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		w.WriteHeader(http.StatusOK)
		fmt.Fprint(w, "event: meta\ndata: {\"scope\":{\"description\":\"전체\"}}\n\n")
		w.(http.Flusher).Flush()

		<-release

		fmt.Fprint(w, "event: done\ndata: {\"reason\":\"COMPLETED\"}\n\n")
		w.(http.Flusher).Flush()
	}))
	defer backend.Close()
	defer close(release)

	gw := newGateway(t, baseConfig(backend.URL))

	res, err := http.Post(gw.URL+"/api/chat/messages/stream",
		"application/json", strings.NewReader(`{"message":"Nginx SSE 문제를 어떻게 해결했지?"}`))
	if err != nil {
		t.Fatalf("요청 실패: %v", err)
	}
	defer res.Body.Close()

	if got := res.Header.Get("X-Accel-Buffering"); got != "no" {
		t.Errorf("X-Accel-Buffering=%q, nginx 가 응답을 모아버린다", got)
	}

	type readResult struct {
		text string
		err  error
	}
	done := make(chan readResult, 1)
	go func() {
		buf := make([]byte, 256)
		n, err := res.Body.Read(buf)
		done <- readResult{string(buf[:n]), err}
	}()

	select {
	case got := <-done:
		if got.err != nil && got.err != io.EOF {
			t.Fatalf("첫 조각 읽기 실패: %v", got.err)
		}
		if !strings.Contains(got.text, "event: meta") {
			t.Errorf("첫 조각에 meta 가 없다: %q", got.text)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("첫 조각이 도착하지 않았다. 어딘가에서 버퍼링되고 있다")
	}
}

func TestProxy_SetsForwardedHeaders(t *testing.T) {
	seen := make(chan http.Header, 1)

	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen <- r.Header.Clone()
		w.WriteHeader(http.StatusOK)
	}))
	defer backend.Close()

	cfg := baseConfig(backend.URL)
	cfg.TrustProxyHeaders = true
	gw := newGateway(t, cfg)

	req, _ := http.NewRequest(http.MethodPost, gw.URL+"/api/chat/messages",
		strings.NewReader(`{"message":"x"}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Forwarded-For", "203.0.113.9")
	req.Header.Set("X-Forwarded-Host", "assistant.example.com")
	req.Header.Set("X-Forwarded-Proto", "https")
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("요청 실패: %v", err)
	}
	res.Body.Close()

	h := <-seen
	for _, name := range []string{"X-Forwarded-For", "X-Forwarded-Proto", "X-Forwarded-Host"} {
		if h.Get(name) == "" {
			t.Errorf("%s 가 백엔드로 전달되지 않았다", name)
		}
	}
	if h.Get("X-Request-Id") == "" {
		t.Error("X-Request-Id 가 전달되지 않아 두 프로세스의 로그를 이어 볼 수 없다")
	}
	if got := h.Get("X-Forwarded-Proto"); got != "https" {
		t.Errorf("X-Forwarded-Proto=%q, 신뢰 프록시의 HTTPS 정보가 보존되지 않았다", got)
	}
	if got := h.Get("X-Forwarded-Host"); got != "assistant.example.com" {
		t.Errorf("X-Forwarded-Host=%q, 외부 호스트가 보존되지 않았다", got)
	}
	if got := h.Get("X-Forwarded-For"); !strings.HasPrefix(got, "203.0.113.9,") {
		t.Errorf("X-Forwarded-For=%q, 신뢰 프록시의 클라이언트 주소가 사라졌다", got)
	}
}

func TestProxy_IgnoresSpoofedForwardedFor_WhenProxyNotTrusted(t *testing.T) {
	seen := make(chan string, 1)

	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen <- r.Header.Get("X-Forwarded-For")
		w.WriteHeader(http.StatusOK)
	}))
	defer backend.Close()

	cfg := baseConfig(backend.URL)
	cfg.TrustProxyHeaders = false
	gw := newGateway(t, cfg)

	req, _ := http.NewRequest(http.MethodGet, gw.URL+"/api/knowledge/documents", nil)
	req.Header.Set("X-Forwarded-For", "203.0.113.9")
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("요청 실패: %v", err)
	}
	res.Body.Close()

	if got := <-seen; strings.Contains(got, "203.0.113.9") {
		t.Errorf("위조된 X-Forwarded-For 가 그대로 전달됐다: %q", got)
	}
}

func TestRateLimit_RejectsChatBeyondBurst(t *testing.T) {
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer backend.Close()

	cfg := baseConfig(backend.URL)
	cfg.RateLimitEnabled = true
	cfg.RateLimitBurst = 2
	gw := newGateway(t, cfg)

	post := func() int {
		res, err := http.Post(gw.URL+"/api/chat/messages",
			"application/json", strings.NewReader(`{"message":"x"}`))
		if err != nil {
			t.Fatalf("요청 실패: %v", err)
		}
		defer res.Body.Close()
		return res.StatusCode
	}

	if got := post(); got != http.StatusOK {
		t.Fatalf("1번째 요청 status=%d, 버스트 안이라 통과해야 한다", got)
	}
	if got := post(); got != http.StatusOK {
		t.Fatalf("2번째 요청 status=%d, 버스트 안이라 통과해야 한다", got)
	}

	res, err := http.Post(gw.URL+"/api/chat/messages",
		"application/json", strings.NewReader(`{"message":"x"}`))
	if err != nil {
		t.Fatalf("요청 실패: %v", err)
	}
	defer res.Body.Close()

	if res.StatusCode != http.StatusTooManyRequests {
		t.Errorf("3번째 요청 status=%d, 429 여야 한다", res.StatusCode)
	}
	if res.Header.Get("Retry-After") == "" {
		t.Error("Retry-After 가 없다")
	}
}

func TestRateLimit_DoesNotApplyToKnowledgeList(t *testing.T) {
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer backend.Close()

	cfg := baseConfig(backend.URL)
	cfg.RateLimitEnabled = true
	cfg.RateLimitBurst = 1
	gw := newGateway(t, cfg)

	for i := 0; i < 5; i++ {
		res, err := http.Get(gw.URL + "/api/knowledge/documents?page=0&size=20")
		if err != nil {
			t.Fatalf("요청 실패: %v", err)
		}
		res.Body.Close()
		if res.StatusCode != http.StatusOK {
			t.Fatalf("%d번째 지식 목록 조회 status=%d, 제한 대상이 아니다", i+1, res.StatusCode)
		}
	}
}

func TestHealthz_UpWhenBackendIsDown(t *testing.T) {
	gw := newGateway(t, baseConfig("http://127.0.0.1:1"))

	res, err := http.Get(gw.URL + "/healthz")
	if err != nil {
		t.Fatalf("요청 실패: %v", err)
	}
	defer res.Body.Close()

	if res.StatusCode != http.StatusOK {
		t.Errorf("/healthz status=%d, 백엔드와 무관하게 200 이어야 한다", res.StatusCode)
	}
}

func TestReadyz_DownWhenBackendIsUnreachable(t *testing.T) {
	gw := newGateway(t, baseConfig("http://127.0.0.1:1"))

	res, err := http.Get(gw.URL + "/readyz")
	if err != nil {
		t.Fatalf("요청 실패: %v", err)
	}
	defer res.Body.Close()

	if res.StatusCode != http.StatusServiceUnavailable {
		t.Errorf("/readyz status=%d, 503 이어야 한다", res.StatusCode)
	}
}

func TestUnknownPath_IsNotForwarded(t *testing.T) {
	forwarded := false
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		forwarded = true
		w.WriteHeader(http.StatusOK)
	}))
	defer backend.Close()

	gw := newGateway(t, baseConfig(backend.URL))

	res, err := http.Get(gw.URL + "/internal/metrics")
	if err != nil {
		t.Fatalf("요청 실패: %v", err)
	}
	defer res.Body.Close()

	if forwarded {
		t.Error("명시하지 않은 경로가 백엔드로 전달됐다")
	}
	if res.StatusCode != http.StatusNotFound {
		t.Errorf("status=%d, 404 여야 한다", res.StatusCode)
	}
}

func TestSlowPaths_UseLongerTimeout(t *testing.T) {
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(300 * time.Millisecond)
		w.WriteHeader(http.StatusOK)
	}))
	defer backend.Close()

	cfg := baseConfig(backend.URL)
	cfg.ProxyTimeout = 80 * time.Millisecond
	cfg.SlowTimeout = 2 * time.Second
	gw := newGateway(t, cfg)

	post := func(path string) int {
		res, err := http.Post(gw.URL+path, "application/json", strings.NewReader(`{}`))
		if err != nil {
			t.Fatalf("%s 요청 실패: %v", path, err)
		}
		defer res.Body.Close()
		return res.StatusCode
	}

	for _, path := range []string{
		"/api/chat/messages",
		"/api/knowledge/documents",
		"/api/knowledge/documents/42/reindex",
	} {
		if got := post(path); got != http.StatusOK {
			t.Errorf("%s status=%d, 긴 상한을 써야 하므로 200 이어야 한다", path, got)
		}
	}

	if got := post("/api/auth/login"); got != http.StatusGatewayTimeout {
		t.Errorf("/api/auth/login status=%d, 일반 상한이 걸려 504 여야 한다", got)
	}
}

func dialUpgrade(t *testing.T, gwURL, path string) (net.Conn, *bufio.Reader, *http.Response) {
	t.Helper()

	u, err := url.Parse(gwURL)
	if err != nil {
		t.Fatalf("URL 파싱: %v", err)
	}
	conn, err := net.Dial("tcp", u.Host)
	if err != nil {
		t.Fatalf("연결 실패: %v", err)
	}
	t.Cleanup(func() { conn.Close() })

	fmt.Fprintf(conn, "GET %s HTTP/1.1\r\nHost: %s\r\n"+
		"Upgrade: websocket\r\nConnection: Upgrade\r\n"+
		"Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n",
		path, u.Host)

	br := bufio.NewReader(conn)
	res, err := http.ReadResponse(br, nil)
	if err != nil {
		t.Fatalf("응답 읽기 실패: %v", err)
	}
	return conn, br, res
}

func echoUpgradeBackend(t *testing.T, seen chan<- http.Header) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if seen != nil {
			seen <- r.Header.Clone()
		}
		if !strings.EqualFold(r.Header.Get("Upgrade"), "websocket") {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		conn, buf, err := w.(http.Hijacker).Hijack()
		if err != nil {
			t.Errorf("백엔드 hijack 실패: %v", err)
			return
		}
		defer conn.Close()

		buf.WriteString("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n")
		buf.Flush()

		for {
			line, err := buf.ReadString('\n')
			if err != nil {
				return
			}
			buf.WriteString("echo:" + line)
			buf.Flush()
		}
	}))
}

func TestWebSocket_UpgradeReachesBackendAndBytesFlowBothWays(t *testing.T) {
	seen := make(chan http.Header, 1)
	backend := echoUpgradeBackend(t, seen)
	defer backend.Close()

	gw := newGateway(t, baseConfig(backend.URL))
	conn, br, res := dialUpgrade(t, gw.URL, "/api/chat/ws")

	if res.StatusCode != http.StatusSwitchingProtocols {
		t.Fatalf("status=%d, 101 이어야 한다. 업그레이드가 평범한 HTTP 로 떨어졌다", res.StatusCode)
	}

	if h := <-seen; h.Get("X-Forwarded-For") == "" {
		t.Error("핸드셰이크에 X-Forwarded-For 가 실리지 않았다")
	}

	fmt.Fprint(conn, "ping\n")
	got, err := br.ReadString('\n')
	if err != nil {
		t.Fatalf("업그레이드 후 읽기 실패: %v", err)
	}
	if got != "echo:ping\n" {
		t.Errorf("되돌아온 바이트=%q, 양방향 복사가 되지 않는다", got)
	}
}

func TestWebSocket_UsesConnectionLifetimeNotProxyTimeout(t *testing.T) {
	backend := echoUpgradeBackend(t, nil)
	defer backend.Close()

	cfg := baseConfig(backend.URL)
	cfg.ProxyTimeout = 100 * time.Millisecond
	cfg.WebSocketTimeout = 5 * time.Second
	gw := newGateway(t, cfg)

	conn, br, res := dialUpgrade(t, gw.URL, "/api/chat/ws")
	if res.StatusCode != http.StatusSwitchingProtocols {
		t.Fatalf("status=%d, 101 이어야 한다", res.StatusCode)
	}

	time.Sleep(400 * time.Millisecond)

	fmt.Fprint(conn, "still-there\n")
	got, err := br.ReadString('\n')
	if err != nil {
		t.Fatalf("ProxyTimeout(100ms)이 소켓에 적용되고 있다: %v", err)
	}
	if got != "echo:still-there\n" {
		t.Errorf("되돌아온 바이트=%q", got)
	}
}

func TestWebSocket_HandshakeIsRateLimited(t *testing.T) {
	backend := echoUpgradeBackend(t, nil)
	defer backend.Close()

	cfg := baseConfig(backend.URL)
	cfg.RateLimitEnabled = true
	cfg.RateLimitBurst = 1
	gw := newGateway(t, cfg)

	if _, _, res := dialUpgrade(t, gw.URL, "/api/chat/ws"); res.StatusCode != http.StatusSwitchingProtocols {
		t.Fatalf("1번째 핸드셰이크 status=%d, 버스트 안이라 통과해야 한다", res.StatusCode)
	}
	if _, _, res := dialUpgrade(t, gw.URL, "/api/chat/ws"); res.StatusCode != http.StatusTooManyRequests {
		t.Errorf("2번째 핸드셰이크 status=%d, 429 여야 한다", res.StatusCode)
	}
}
