package main

import (
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
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
	var priv *ecdh.PrivateKey
	seed, err := base64.RawURLEncoding.DecodeString(os.Getenv("YTPARTY_RELAY_PRIVATE_KEY"))
	if err == nil {
		priv, err = ecdh.X25519().NewPrivateKey(seed)
	}
	if err != nil {
		example, err := ecdh.X25519().GenerateKey(rand.Reader)
		if err != nil {
			log.Fatal(err)
		}
		log.Print("FATAL: YTPARTY_RELAY_PRIVATE_KEY is required and must be a base64url X25519 private key.")
		log.Printf("       for example: YTPARTY_RELAY_PRIVATE_KEY=%s", base64.RawURLEncoding.EncodeToString(example.Bytes()))
		log.Fatal("       set YTPARTY_RELAY_PRIVATE_KEY and start again")
	}
	host := env("YTPARTY_RELAY_HOST", "0.0.0.0")
	port := env("YTPARTY_RELAY_PORT", "25599")
	r := &relay{
		priv:          priv,
		pub:           priv.PublicKey().Bytes(),
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
	slog.Info("public key for clients", "key", base64.RawURLEncoding.EncodeToString(r.pub))
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
