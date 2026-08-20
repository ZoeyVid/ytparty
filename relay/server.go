package main

import (
	"context"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/mlkem"
	"crypto/rand"
	"log/slog"
	"net"
	"strings"
	"sync"
	"time"
)

const (
	maxConns   = 512
	maxPerIP   = 16
	ipBurst    = 30
	ipRefill   = 0.5
	msgBurst   = 16
	msgRate    = 4
	tokenTTL   = time.Hour
	sweepEvery = 10 * time.Minute
)

type conn struct {
	tok  string
	uuid string
	name string
	aead cipher.AEAD
	c    net.Conn
	out  chan []byte
	quit chan struct{}
	once sync.Once
}

func (cc *conn) stop() {
	cc.once.Do(func() { close(cc.quit); cc.c.Close() })
}

type tokenInfo struct {
	uuid string
	seen time.Time
}

type ipState struct {
	conns  int
	bucket float64
	last   time.Time
}

func (r *relay) writer(cc *conn) {
	var ctr uint64
	for {
		select {
		case pt := <-cc.out:
			ct := cc.aead.Seal(nil, nonce(1, ctr), pt, nil)
			ctr++
			if writeFrame(cc.c, ct) != nil {
				cc.stop()
				return
			}
		case <-cc.quit:
			return
		}
	}
}
func (r *relay) admit(c net.Conn) (string, bool) {
	ip, _, _ := net.SplitHostPort(c.RemoteAddr().String())
	now := time.Now()
	r.mu.Lock()
	defer r.mu.Unlock()
	st := r.perIP[ip]
	if st == nil {
		st = &ipState{bucket: ipBurst, last: now}
		r.perIP[ip] = st
	}
	st.bucket = min(float64(ipBurst), st.bucket+now.Sub(st.last).Seconds()*ipRefill)
	st.last = now
	if r.activeConns >= maxConns || st.conns >= maxPerIP || st.bucket < 1 {
		slog.Warn("rejected", "ip", ip, "conns", r.activeConns, "ipConns", st.conns, "bucket", st.bucket)
		return "", false
	}
	st.bucket--
	st.conns++
	r.activeConns++
	return ip, true
}
func (r *relay) release(ip string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.activeConns--
	if st := r.perIP[ip]; st != nil {
		st.conns--
		if st.conns <= 0 && st.bucket >= ipBurst {
			delete(r.perIP, ip)
		}
	}
}

func (r *relay) handle(c net.Conn) {
	defer func() {
		if e := recover(); e != nil {
			slog.Error("handle panic", "err", e)
		}
	}()
	defer c.Close()
	ip, ok := r.admit(c)
	if !ok {
		return
	}
	defer r.release(ip)

	c.SetReadDeadline(time.Now().Add(10 * time.Second))
	msg1, err := readFrame(c)
	if err != nil || len(msg1) != 16+32+1184 {
		return
	}
	xcPub, err := ecdh.X25519().NewPublicKey(msg1[16:48])
	if err != nil {
		return
	}
	ek, err := mlkem.NewEncapsulationKey768(msg1[48:])
	if err != nil {
		return
	}
	xs, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return
	}
	ssx, err := xs.ECDH(xcPub)
	if err != nil {
		return
	}
	ssm, ct := ek.Encapsulate()
	sn := make([]byte, 16)
	if _, err := rand.Read(sn); err != nil {
		return
	}
	msg2 := append(append(append(make([]byte, 0, 16+32+len(ct)), sn...), xs.PublicKey().Bytes()...), ct...)
	if err := writeFrame(c, msg2); err != nil {
		return
	}
	g, err := gcm(deriveSession(r.key, msg1, msg2, ssx, ssm))
	if err != nil {
		return
	}
	encID, err := readFrame(c)
	if err != nil {
		return
	}
	id, err := g.Open(nil, nonce(0, 0), encID, nil)
	if err != nil {
		slog.Warn("auth failed", "ip", ip)
		return
	}
	c.SetReadDeadline(time.Time{})
	rd := &rdr{b: id}
	name := string(rd.blob())
	uuid := string(rd.blob())
	token := string(rd.blob())
	if rd.bad || name == "" || uuid == "" {
		return
	}

	r.mu.Lock()
	info, exists := r.tokens[token]
	newToken := token == "" || !exists || info.uuid != uuid
	if newToken {
		token = r.newToken()
	}
	r.tokens[token] = tokenInfo{uuid: uuid, seen: time.Now()}
	cc := &conn{tok: token, uuid: uuid, name: name, aead: g, c: c, out: make(chan []byte, 256), quit: make(chan struct{})}
	ack := &wtr{}
	ack.u8(1)
	ack.str(token)
	cc.out <- ack.b
	go r.writer(cc)
	old := r.conns[token]
	r.conns[token] = cc
	r.mu.Unlock()
	tokenKind := "new"
	if !newToken {
		tokenKind = "resume"
	}
	if old != nil {
		old.stop()
		tokenKind = "replace"
	}
	slog.Info("connect", "name", name, "ip", ip, "token", tokenKind)
	r.mu.Lock()
	if p := r.of(token); p != nil {
		r.send(token, r.stateBytes(p, token))
	}
	r.mu.Unlock()

	bucket := float64(msgBurst)
	last := time.Now()
	var recvCtr uint64 = 1
	for {
		fr, err := readFrame(c)
		if err != nil {
			break
		}
		now := time.Now()
		bucket = min(float64(msgBurst), bucket+now.Sub(last).Seconds()*msgRate)
		last = now
		if bucket < 1 {
			recvCtr++
			continue
		}
		bucket--
		pt, err := g.Open(nil, nonce(0, recvCtr), fr, nil)
		if err != nil {
			break
		}
		recvCtr++
		r.mu.Lock()
		r.onReceive(cc, pt)
		r.mu.Unlock()
	}
	cc.stop()
	r.drop(cc)
	slog.Info("disconnect", "name", name)
}

func (r *relay) drop(cc *conn) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.conns[cc.tok] == cc {
		delete(r.conns, cc.tok)
		r.tokens[cc.tok] = tokenInfo{uuid: cc.uuid, seen: time.Now()}
		r.afterLeave(r.leave(cc.tok))
		if r.onlineByName(cc.name) == "" {
			name := strings.ToLower(cc.name)
			for _, p := range r.byID {
				delete(p.invites, name)
			}
		}
	}
}

func (r *relay) sweepTokens(ctx context.Context) {
	t := time.NewTicker(sweepEvery)
	defer t.Stop()
	for {
		select {
		case <-t.C:
			now := time.Now()
			r.mu.Lock()
			for tok, info := range r.tokens {
				if r.conns[tok] == nil && now.Sub(info.seen) > tokenTTL {
					delete(r.tokens, tok)
				}
			}
			for ip, st := range r.perIP {
				if st.conns <= 0 && now.Sub(st.last) > tokenTTL {
					delete(r.perIP, ip)
				}
			}
			r.mu.Unlock()
		case <-ctx.Done():
			return
		}
	}
}
