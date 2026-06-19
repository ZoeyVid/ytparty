// YT Party relay: password-gated, AES-256-GCM encrypted TCP relay that syncs
// listening parties for clients whose Minecraft server has neither the Paper
// plugin nor the Fabric server mod. Standard library only, no external modules.
//
// Clients are identified by a relay-issued token (trust on first use, kept in
// RAM only): the relay hands a fresh token to a client that presents none, and
// recognizes a returning client by the token it persisted. Parties, members and
// connections are keyed by token, so two clients may share a Minecraft UUID and
// still be distinct members; members sharing a claimed UUID are flagged so the
// client can highlight them.
//
// Config via environment:
//
//	YTPARTY_RELAY_PASSWORD    (required) shared pre-shared key, ASCII only
//	YTPARTY_RELAY_HOST        bind host (default 0.0.0.0)
//	YTPARTY_RELAY_PORT        bind port (default 25599)
//	YTPARTY_DEFAULT_PUBLIC    new parties public by default (default false)
//	YTPARTY_PUBLIC_JOIN_LEVEL listen|invite|manage (default listen)
//
// Connection cap and rate limits are fixed sensible defaults (see the const
// block below) and intentionally not configurable.
package main

import (
	"context"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/mlkem"
	"crypto/rand"
	"encoding/binary"
	"io"
	"log"
	"net"
	"os"
	"os/signal"
	"slices"
	"strings"
	"sync"
	"syscall"
	"time"
)

const (
	listen = 0
	invite = 1
	manage = 2
)

const (
	maxConns   = 512
	maxPerIP   = 16
	ipBurst    = 30
	ipRefill   = 0.5
	msgBurst   = 128
	msgRate    = 64
	tokenTTL   = time.Hour
	sweepEvery = 10 * time.Minute
)

const (
	cCreate = iota
	cJoin
	cLeave
	cInvite
	cSetLevel
	cAdd
	cRemove
	cMove
	cSetIndex
	cSetPaused
	cSetPosition
	cSetPublic
	cSetAutoRemove
)

const (
	sState = iota
	sInvited
	sMessage
	sLeft
	sSeek
)

func levelFromName(s string) int {
	switch strings.ToLower(s) {
	case "manage":
		return manage
	case "invite":
		return invite
	default:
		return listen
	}
}

func lvl(b int) int {
	if b < listen || b > manage {
		return listen
	}
	return b
}

func capped(level, mx int) int {
	if level > mx {
		return mx
	}
	return level
}

// ---- byte helpers ----
type rdr struct {
	b   []byte
	i   int
	bad bool
}

func (r *rdr) ok(n int) bool {
	if r.bad || r.i+n > len(r.b) {
		r.bad = true
		return false
	}
	return true
}
func (r *rdr) u8() int {
	if !r.ok(1) {
		return 0
	}
	v := int(r.b[r.i])
	r.i++
	return v
}
func (r *rdr) boolean() bool { return r.u8() != 0 }
func (r *rdr) i32() int {
	if !r.ok(4) {
		return 0
	}
	v := int(int32(binary.BigEndian.Uint32(r.b[r.i:])))
	r.i += 4
	return v
}
func (r *rdr) i64() int64 {
	if !r.ok(8) {
		return 0
	}
	v := int64(binary.BigEndian.Uint64(r.b[r.i:]))
	r.i += 8
	return v
}
func (r *rdr) blob() []byte {
	if !r.ok(2) {
		return nil
	}
	n := int(binary.BigEndian.Uint16(r.b[r.i:]))
	r.i += 2
	if !r.ok(n) {
		return nil
	}
	v := r.b[r.i : r.i+n]
	r.i += n
	return append([]byte(nil), v...)
}

type wtr struct{ b []byte }

func (w *wtr) u8(v int) { w.b = append(w.b, byte(v)) }
func (w *wtr) boolean(v bool) {
	if v {
		w.u8(1)
	} else {
		w.u8(0)
	}
}
func (w *wtr) i32(v int) {
	var t [4]byte
	binary.BigEndian.PutUint32(t[:], uint32(int32(v)))
	w.b = append(w.b, t[:]...)
}
func (w *wtr) i64(v int64) {
	var t [8]byte
	binary.BigEndian.PutUint64(t[:], uint64(v))
	w.b = append(w.b, t[:]...)
}
func (w *wtr) blob(b []byte) {
	var t [2]byte
	binary.BigEndian.PutUint16(t[:], uint16(len(b)))
	w.b = append(w.b, t[:]...)
	w.b = append(w.b, b...)
}
func (w *wtr) str(s string) { w.blob([]byte(s)) }

// ---- model ----
type party struct {
	id         string
	members    map[string]int
	invites    map[string]int
	tracks     [][2][]byte
	isPublic   bool
	pubJoinLvl int
	curIndex   int
	paused     bool
	autoRemove bool
}

func (p *party) level(tok string) int {
	if l, ok := p.members[tok]; ok {
		return l
	}
	return listen
}
func (p *party) hasManager() bool {
	for _, l := range p.members {
		if l == manage {
			return true
		}
	}
	return false
}
func (p *party) move(from, to int) {
	if from < 0 || from >= len(p.tracks) || to < 0 || to >= len(p.tracks) {
		return
	}
	t := p.tracks[from]
	p.tracks = slices.Delete(p.tracks, from, from+1)
	p.tracks = slices.Insert(p.tracks, to, t)
}

type conn struct {
	tok  string
	uuid string
	name []byte
	aead cipher.AEAD
	c    net.Conn
	out  chan []byte
	quit chan struct{}
	once sync.Once
}

func (cc *conn) stop() {
	cc.once.Do(func() {
		close(cc.quit)
		cc.c.Close()
	})
}

type ipState struct {
	conns  int
	bucket float64
	last   time.Time
}

type relay struct {
	mu            sync.Mutex
	key           []byte
	byID          map[string]*party
	playerToParty map[string]string
	defPublic     bool
	defLevel      int
	conns         map[string]*conn
	tokens        map[string]time.Time
	perIP         map[string]*ipState
	activeConns   int
}

type leaveRes struct {
	p         *party
	disbanded bool
}

func (r *relay) of(tok string) *party {
	if pid, ok := r.playerToParty[tok]; ok {
		return r.byID[pid]
	}
	return nil
}
func (r *relay) create(tok string) *party {
	p := &party{id: r.newID(), members: map[string]int{tok: manage}, invites: map[string]int{}, isPublic: r.defPublic, pubJoinLvl: r.defLevel, curIndex: -1, autoRemove: true}
	r.byID[p.id] = p
	r.playerToParty[tok] = p.id
	return p
}
func (r *relay) joinParty(tok, pid string) *party {
	p := r.byID[pid]
	if p == nil {
		return nil
	}
	if _, ok := p.members[tok]; ok {
		return p
	}
	name := strings.ToLower(string(r.nameOf(tok)))
	if l, ok := p.invites[name]; ok {
		delete(p.invites, name)
		p.members[tok] = l
	} else if p.isPublic {
		p.members[tok] = p.pubJoinLvl
	} else {
		return nil
	}
	r.playerToParty[tok] = pid
	return p
}
func (r *relay) leave(tok string) *leaveRes {
	pid, ok := r.playerToParty[tok]
	if !ok {
		return nil
	}
	delete(r.playerToParty, tok)
	p := r.byID[pid]
	if p == nil {
		return nil
	}
	delete(p.members, tok)
	return r.finish(p)
}
func (r *relay) setLevel(p *party, target string, level int) *leaveRes {
	if _, ok := p.members[target]; !ok {
		return nil
	}
	p.members[target] = level
	return r.finish(p)
}
func (r *relay) finish(p *party) *leaveRes {
	disbanded := len(p.members) == 0 || !p.hasManager()
	if disbanded {
		for m := range p.members {
			delete(r.playerToParty, m)
		}
		delete(r.byID, p.id)
	}
	return &leaveRes{p, disbanded}
}

// ---- io ----
func (r *relay) nameOf(tok string) []byte {
	if c, ok := r.conns[tok]; ok {
		return c.name
	}
	return []byte("?")
}
func (r *relay) uuidOf(tok string) string {
	if c, ok := r.conns[tok]; ok {
		return c.uuid
	}
	return tok
}
func (r *relay) memberByName(p *party, name []byte) string {
	want := strings.ToLower(string(name))
	for tok := range p.members {
		if strings.ToLower(string(r.nameOf(tok))) == want {
			return tok
		}
	}
	return ""
}
func (r *relay) onlineByName(name []byte) string {
	want := strings.ToLower(string(name))
	for tok, c := range r.conns {
		if strings.ToLower(string(c.name)) == want {
			return tok
		}
	}
	return ""
}
func (r *relay) send(tok string, payload []byte) {
	c := r.conns[tok]
	if c == nil {
		return
	}
	select {
	case c.out <- payload:
	default:
		c.stop()
	}
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
func (r *relay) stateBytes(p *party, viewer string) []byte {
	counts := map[string]int{}
	for tok := range p.members {
		counts[r.uuidOf(tok)]++
	}
	w := &wtr{}
	w.u8(sState)
	w.str(p.id)
	w.u8(p.level(viewer))
	w.boolean(p.isPublic)
	w.u8(p.pubJoinLvl)
	w.boolean(p.paused)
	w.i32(p.curIndex)
	w.boolean(p.autoRemove)
	w.i32(len(p.tracks))
	for _, t := range p.tracks {
		w.blob(t[0])
		w.blob(t[1])
	}
	w.i32(len(p.members))
	for tok, l := range p.members {
		w.blob(r.nameOf(tok))
		w.u8(l)
		w.boolean(counts[r.uuidOf(tok)] > 1)
	}
	return w.b
}
func (r *relay) broadcast(p *party) {
	for tok := range p.members {
		r.send(tok, r.stateBytes(p, tok))
	}
}
func (r *relay) afterLeave(res *leaveRes) {
	if res == nil {
		return
	}
	if res.disbanded {
		w := &wtr{}
		w.u8(sLeft)
		for tok := range res.p.members {
			r.send(tok, w.b)
		}
	} else {
		r.broadcast(res.p)
	}
}
func (r *relay) msg(tok, text string) {
	w := &wtr{}
	w.u8(sMessage)
	w.str(text)
	r.send(tok, w.b)
}

func (r *relay) onReceive(c *conn, payload []byte) {
	if len(payload) == 0 {
		return
	}
	rd := &rdr{b: payload}
	op := rd.u8()
	tok := c.tok
	switch op {
	case cCreate:
		r.afterLeave(r.leave(tok))
		p := r.create(tok)
		log.Printf("party %s created by %s", p.id, c.name)
		r.broadcast(p)
	case cJoin:
		pid := string(rd.blob())
		if rd.bad {
			return
		}
		r.doJoin(tok, pid)
	case cLeave:
		res := r.leave(tok)
		w := &wtr{}
		w.u8(sLeft)
		r.send(tok, w.b)
		r.afterLeave(res)
	case cInvite:
		name := rd.blob()
		level := rd.u8()
		if rd.bad {
			return
		}
		r.doInvite(tok, name, lvl(level))
	case cSetLevel:
		name := rd.blob()
		level := rd.u8()
		if rd.bad {
			return
		}
		r.doSetLevel(tok, name, lvl(level))
	case cSetPublic:
		isPublic := rd.boolean()
		level := rd.u8()
		if rd.bad {
			return
		}
		r.doSetPublic(tok, isPublic, lvl(level))
	default:
		r.control(tok, byte(op), rd)
	}
}

func (r *relay) doJoin(tok, pid string) {
	p := r.byID[pid]
	_, isMember := nilGet(p, tok)
	invited := p != nil && nilInv(p, strings.ToLower(string(r.nameOf(tok))))
	if p == nil || !(isMember || invited || p.isPublic) {
		r.msg(tok, "Cannot join "+pid)
		return
	}
	if isMember {
		r.broadcast(p)
		return
	}
	r.afterLeave(r.leave(tok))
	if jp := r.joinParty(tok, pid); jp != nil {
		log.Printf("%s joined party %s", r.nameOf(tok), pid)
		r.broadcast(jp)
	}
}
func nilGet(p *party, tok string) (int, bool) {
	if p == nil {
		return 0, false
	}
	v, ok := p.members[tok]
	return v, ok
}
func nilInv(p *party, name string) bool {
	if p == nil {
		return false
	}
	_, ok := p.invites[name]
	return ok
}

func (r *relay) doInvite(tok string, name []byte, level int) {
	p := r.of(tok)
	if p == nil || p.level(tok) < invite {
		return
	}
	g := capped(level, p.level(tok))
	p.invites[strings.ToLower(string(name))] = g
	if target := r.onlineByName(name); target != "" {
		w := &wtr{}
		w.u8(sInvited)
		w.blob(r.nameOf(tok))
		w.str(p.id)
		w.u8(g)
		r.send(target, w.b)
	}
}
func (r *relay) doSetLevel(tok string, name []byte, level int) {
	p := r.of(tok)
	if p == nil || p.level(tok) != manage {
		return
	}
	target := r.memberByName(p, name)
	if target == "" {
		return
	}
	r.afterLeave(r.setLevel(p, target, level))
}
func (r *relay) doSetPublic(tok string, isPublic bool, level int) {
	p := r.of(tok)
	if p == nil || p.level(tok) != manage {
		return
	}
	p.isPublic = isPublic
	p.pubJoinLvl = level
	r.broadcast(p)
}

func (r *relay) control(tok string, op byte, rd *rdr) {
	p := r.of(tok)
	if p == nil {
		return
	}
	if p.level(tok) != manage {
		r.send(tok, r.stateBytes(p, tok))
		return
	}
	switch int(op) {
	case cAdd:
		uri := capBytes(rd.blob(), 1000)
		title := capBytes(rd.blob(), 200)
		if rd.bad {
			return
		}
		if len(p.tracks) >= 500 {
			return
		}
		p.tracks = append(p.tracks, [2][]byte{uri, title})
		if p.curIndex < 0 {
			p.curIndex = 0
		}
	case cRemove:
		i := rd.i32()
		if rd.bad {
			return
		}
		if i >= 0 && i < len(p.tracks) {
			p.tracks = slices.Delete(p.tracks, i, i+1)
			if i < p.curIndex {
				p.curIndex--
			}
			p.curIndex = min(p.curIndex, len(p.tracks)-1)
		}
	case cMove:
		from := rd.i32()
		to := rd.i32()
		if rd.bad {
			return
		}
		p.move(from, to)
		if from == p.curIndex {
			p.curIndex = to
		} else if from < p.curIndex && to >= p.curIndex {
			p.curIndex--
		} else if from > p.curIndex && to <= p.curIndex {
			p.curIndex++
		}
	case cSetIndex:
		i := rd.i32()
		if rd.bad {
			return
		}
		if p.autoRemove && i == p.curIndex+1 && p.curIndex >= 0 && p.curIndex < len(p.tracks) {
			cur := p.curIndex
			p.tracks = slices.Delete(p.tracks, cur, cur+1)
			p.curIndex = min(cur, len(p.tracks)-1)
		} else {
			p.curIndex = max(-1, min(i, len(p.tracks)-1))
		}
		p.paused = false
	case cSetPaused:
		v := rd.boolean()
		if rd.bad {
			return
		}
		p.paused = v
	case cSetAutoRemove:
		v := rd.boolean()
		if rd.bad {
			return
		}
		p.autoRemove = v
	case cSetPosition:
		ms := rd.i64()
		if rd.bad {
			return
		}
		w := &wtr{}
		w.u8(sSeek)
		w.i64(ms)
		for m := range p.members {
			r.send(m, w.b)
		}
		return
	default:
		return
	}
	r.broadcast(p)
}

func capBytes(b []byte, max int) []byte {
	if len(b) <= max {
		return b
	}
	return b[:max]
}

// ---- framing + connection ----
func writeFrame(c net.Conn, b []byte) error {
	buf := make([]byte, 4+len(b))
	binary.BigEndian.PutUint32(buf, uint32(len(b)))
	copy(buf[4:], b)
	c.SetWriteDeadline(time.Now().Add(5 * time.Second))
	_, err := c.Write(buf)
	return err
}
func readFrame(c net.Conn) ([]byte, error) {
	var h [4]byte
	if _, err := io.ReadFull(c, h[:]); err != nil {
		return nil, err
	}
	n := binary.BigEndian.Uint32(h[:])
	if n > 1<<20 {
		return nil, io.ErrShortBuffer
	}
	b := make([]byte, n)
	_, err := io.ReadFull(c, b)
	return b, err
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
		log.Printf("rejected %s (conns=%d ip=%d bucket=%.1f)", ip, r.activeConns, st.conns, st.bucket)
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
	msg2 := make([]byte, 0, 16+32+len(ct))
	msg2 = append(msg2, sn...)
	msg2 = append(msg2, xs.PublicKey().Bytes()...)
	msg2 = append(msg2, ct...)
	if err := writeFrame(c, msg2); err != nil {
		return
	}
	g := gcm(deriveSession(r.key, msg1, msg2, ssx, ssm))
	encID, err := readFrame(c)
	if err != nil {
		return
	}
	id, err := g.Open(nil, nonce(0, 0), encID, nil)
	if err != nil {
		return // wrong PSK or tampering
	}
	c.SetReadDeadline(time.Time{})
	rd := &rdr{b: id}
	name := rd.blob()
	uuid := string(rd.blob())
	token := string(rd.blob())
	if rd.bad || uuid == "" {
		return
	}

	r.mu.Lock()
	if token == "" || r.tokens[token].IsZero() {
		token = r.newToken()
	}
	r.tokens[token] = time.Now()
	cc := &conn{tok: token, uuid: uuid, name: name, aead: g, c: c, out: make(chan []byte, 256), quit: make(chan struct{})}
	ack := &wtr{}
	ack.u8(1)
	ack.str(token)
	cc.out <- ack.b
	go r.writer(cc)
	old := r.conns[token]
	r.conns[token] = cc
	r.mu.Unlock()
	if old != nil {
		old.stop()
	}
	log.Printf("connect: %s from %s", name, ip)
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
			log.Printf("flood: dropping %s", name)
			break
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
	log.Printf("disconnect: %s", name)
}

func (r *relay) drop(cc *conn) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.conns[cc.tok] == cc {
		delete(r.conns, cc.tok)
		r.tokens[cc.tok] = time.Now()
		r.afterLeave(r.leave(cc.tok))
	}
}

func (r *relay) sweepTokens() {
	for range time.Tick(sweepEvery) {
		now := time.Now()
		r.mu.Lock()
		for tok, seen := range r.tokens {
			if r.conns[tok] == nil && now.Sub(seen) > tokenTTL {
				delete(r.tokens, tok)
			}
		}
		r.mu.Unlock()
	}
}

func genSecret(n int) string {
	const charset = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		log.Fatal(err)
	}
	for i := range b {
		b[i] = charset[int(b[i])%len(charset)]
	}
	return string(b)
}

func (r *relay) newID() string {
	for {
		id := genSecret(8)
		if _, ok := r.byID[id]; !ok {
			return id
		}
	}
}
func (r *relay) newToken() string {
	for {
		t := genSecret(24)
		if _, ok := r.tokens[t]; !ok {
			return t
		}
	}
}

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
	for i := 0; i < len(pw); i++ {
		if pw[i] < 0x20 || pw[i] > 0x7e {
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
		tokens:        map[string]time.Time{},
		perIP:         map[string]*ipState{},
		defPublic:     strings.ToLower(env("YTPARTY_DEFAULT_PUBLIC", "false")) == "true",
		defLevel:      levelFromName(env("YTPARTY_PUBLIC_JOIN_LEVEL", "listen")),
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	ln, err := net.Listen("tcp", net.JoinHostPort(host, port))
	if err != nil {
		log.Fatal(err)
	}
	log.Printf("YT Party relay (encrypted) listening on %s:%s", host, port)
	go r.sweepTokens()
	go func() {
		<-ctx.Done()
		ln.Close()
	}()
	for {
		c, err := ln.Accept()
		if err != nil {
			if ctx.Err() != nil {
				log.Print("shutdown")
				return
			}
			continue
		}
		if tc, ok := c.(*net.TCPConn); ok {
			tc.SetKeepAlive(true)
			tc.SetKeepAlivePeriod(30 * time.Second)
		}
		go r.handle(c)
	}
}
