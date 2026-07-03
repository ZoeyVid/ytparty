package main

import (
	"context"
	"log"
	"log/slog"
	"net"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"
)

func env(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func main() {
	pw := os.Getenv("YTPARTY_RELAY_PASSWORD")
	if pw == "" {
		log.Print("FATAL: YTPARTY_RELAY_PASSWORD is required and was not set.")
		log.Printf("       for example: YTPARTY_RELAY_PASSWORD=%s", genSecret(24))
		log.Fatal("       set YTPARTY_RELAY_PASSWORD (printable ASCII) and start again")
	}
	for _, ch := range []byte(pw) {
		if ch < 0x20 || ch > 0x7e {
			log.Fatal("FATAL: YTPARTY_RELAY_PASSWORD must be printable ASCII only")
		}
	}
	host := env("YTPARTY_RELAY_HOST", "0.0.0.0")
	port := env("YTPARTY_RELAY_PORT", "25599")
	r := &relay{
		key:           deriveKey(pw),
		byID:          map[string]*party{},
		playerToParty: map[string]string{},
		conns:         map[string]*conn{},
		tokens:        map[string]tokenInfo{},
		perIP:         map[string]*ipState{},
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	ln, err := net.Listen("tcp", net.JoinHostPort(host, port))
	if err != nil {
		log.Fatal(err)
	}
	slog.Info("listening", "host", host, "port", port)
	go r.sweepTokens(ctx)
	go func() { <-ctx.Done(); ln.Close() }()
	var wg sync.WaitGroup
	for {
		c, err := ln.Accept()
		if err != nil {
			if ctx.Err() != nil {
				break
			}
			continue
		}
		if tc, ok := c.(*net.TCPConn); ok {
			tc.SetKeepAlive(true)
			tc.SetKeepAlivePeriod(30 * time.Second)
		}
		wg.Go(func() { r.handle(c) })
	}
	r.mu.Lock()
	for _, cc := range r.conns {
		cc.stop()
	}
	r.mu.Unlock()
	wg.Wait()
	slog.Info("shutdown")
}
