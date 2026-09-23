package main

import (
	"fmt"
	"net"
	"net/http"
	"os"
	"time"
)

func runHealthcheck() int {
	addr := os.Getenv("GATEWAY_LISTEN_ADDR")
	if addr == "" {
		addr = ":8000"
	}

	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		fmt.Fprintf(os.Stderr, "GATEWAY_LISTEN_ADDR 를 읽을 수 없습니다(%q): %v\n", addr, err)
		return 1
	}
	if host == "" || host == "0.0.0.0" || host == "::" {
		host = "127.0.0.1"
	}

	client := &http.Client{Timeout: 2 * time.Second}
	res, err := client.Get("http://" + net.JoinHostPort(host, port) + "/healthz")
	if err != nil {
		fmt.Fprintf(os.Stderr, "상태 확인 실패: %v\n", err)
		return 1
	}
	defer res.Body.Close()

	if res.StatusCode != http.StatusOK {
		fmt.Fprintf(os.Stderr, "상태 확인 실패: status=%d\n", res.StatusCode)
		return 1
	}
	return 0
}
