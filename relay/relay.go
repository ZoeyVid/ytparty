// YT Party relay: password-gated, AES-256-GCM encrypted TCP relay.
// Standard library only, no external modules.
//
// Config via environment:
//
//	YTPARTY_RELAY_PASSWORD    (required) shared pre-shared key, ASCII only
//	YTPARTY_RELAY_HOST        bind host (default 0.0.0.0)
//	YTPARTY_RELAY_PORT        bind port (default 25599)
//	YTPARTY_DEFAULT_PUBLIC    new parties public by default (default false)
//	YTPARTY_PUBLIC_JOIN_LEVEL listen|invite|manage (default listen)
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
	"log/slog"
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
	cListPublic
	cReportPosition
	cSetSponsorBlock
	cSetRepeat
)
const (
	sState = iota
	sInvited
	sMessage
	sLeft
	sSeek
	sPublicList
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

type mgrReport struct {
	pos int64
	at  time.Time
}

type party struct {
	id         string
	members    map[string]int
	invites    map[string]int
	tracks     [][3][]byte
	isPublic   bool
	pubJoinLvl int
	curIndex   int
	paused     bool
	autoRemove bool
	sbFlags    byte
	repeatOne  bool
	mgrPos     map[string]mgrReport
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
	p := &party{id: r.newID(), members: map[string]int{tok: manage}, invites: map[string]int{}, isPublic: r.defPublic, pubJoinLvl: r.defLevel, curIndex: -1, autoRemove: true, sbFlags: 0x0F, mgrPos: map[string]mgrReport{}}
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
	name := strings.ToLower(r.nameOf(tok))
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

func (r *relay) nameOf(tok string) string {
	if c, ok := r.conns[tok]; ok {
		return c.name
	}
	return "?"
}
func (r *relay) uuidOf(tok string) string {
	if c, ok := r.conns[tok]; ok {
		return c.uuid
	}
	return tok
}
func (r *relay) memberByName(p *party, name string) string {
	want := strings.ToLower(name)
	for tok := range p.members {
		if strings.ToLower(r.nameOf(tok)) == want {
			return tok
		}
	}
	return ""
}
func (r *relay) onlineByName(name string) string {
	want := strings.ToLower(name)
	for tok, c := range r.conns {
		if strings.ToLower(c.name) == want {
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
	w.u8(int(p.sbFlags))
	w.boolean(p.repeatOne)
	w.i32(len(p.tracks))
	for _, t := range p.tracks {
		w.blob(t[0])
		w.blob(t[1])
		w.blob(t[2])
	}
	w.i32(len(p.members))
	for tok, l := range p.members {
		w.str(r.nameOf(tok))
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
		slog.Info("party disbanded", "id", res.p.id)
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
		slog.Info("party created", "id", p.id, "by", c.name)
		r.broadcast(p)
	case cJoin:
		pid := string(rd.blob())
		if rd.bad {
			return
		}
		r.doJoin(tok, pid)
	case cLeave:
		if pid, ok := r.playerToParty[tok]; ok {
			slog.Info("leave", "name", c.name, "party", pid)
		}
		res := r.leave(tok)
		w := &wtr{}
		w.u8(sLeft)
		r.send(tok, w.b)
		r.afterLeave(res)
	case cInvite:
		name := string(rd.blob())
		level := rd.u8()
		if rd.bad {
			return
		}
		r.doInvite(tok, name, lvl(level))
	case cSetLevel:
		name := string(rd.blob())
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
	case cListPublic:
		r.doListPublic(tok)
	case cReportPosition:
		ms := rd.i64()
		if rd.bad {
			return
		}
		r.doReport(tok, ms)
	case cSetSponsorBlock:
		flags := rd.u8()
		if rd.bad {
			return
		}
		r.doSetSponsorBlock(tok, byte(flags))
	case cSetRepeat:
		v := rd.boolean()
		if rd.bad {
			return
		}
		r.doSetRepeat(tok, v)
	default:
		r.control(tok, byte(op), rd)
	}
}

func (r *relay) doJoin(tok, pid string) {
	p := r.byID[pid]
	_, isMember := nilGet(p, tok)
	invited := p != nil && nilInv(p, strings.ToLower(r.nameOf(tok)))
	if p == nil || !(isMember || invited || p.isPublic) {
		slog.Info("join rejected", "name", r.nameOf(tok), "party", pid)
		r.msg(tok, "Cannot join "+pid)
		return
	}
	if isMember {
		r.broadcast(p)
		return
	}
	r.afterLeave(r.leave(tok))
	if jp := r.joinParty(tok, pid); jp != nil {
		slog.Info("join", "name", r.nameOf(tok), "party", pid)
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
func (r *relay) doInvite(tok, name string, level int) {
	p := r.of(tok)
	if p == nil || p.level(tok) < invite {
		return
	}
	g := capped(level, p.level(tok))
	p.invites[strings.ToLower(name)] = g
	if target := r.onlineByName(name); target != "" {
		w := &wtr{}
		w.u8(sInvited)
		w.str(r.nameOf(tok))
		w.str(p.id)
		w.u8(g)
		r.send(target, w.b)
	}
}
func (r *relay) doSetLevel(tok, name string, level int) {
	p := r.of(tok)
	if p == nil || p.level(tok) != manage {
		return
	}
	target := r.memberByName(p, name)
	if target == "" {
		return
	}
	slog.Info("set level", "by", r.nameOf(tok), "target", name, "level", level, "party", p.id)
	r.afterLeave(r.setLevel(p, target, level))
}
func (r *relay) doSetPublic(tok string, isPublic bool, level int) {
	p := r.of(tok)
	if p == nil || p.level(tok) != manage {
		return
	}
	p.isPublic = isPublic
	p.pubJoinLvl = level
	slog.Info("set public", "id", p.id, "public", isPublic, "level", level, "by", r.nameOf(tok))
	r.broadcast(p)
}
func (r *relay) doListPublic(tok string) {
	type entry struct {
		id      string
		members int
		title   string
	}
	var pub []entry
	for _, p := range r.byID {
		if !p.isPublic {
			continue
		}
		title := ""
		if p.curIndex >= 0 && p.curIndex < len(p.tracks) {
			title = string(p.tracks[p.curIndex][1])
		}
		pub = append(pub, entry{p.id, len(p.members), title})
	}
	slices.SortFunc(pub, func(a, b entry) int { return b.members - a.members })
	w := &wtr{}
	w.u8(sPublicList)
	w.i32(len(pub))
	for _, e := range pub {
		w.str(e.id)
		w.i32(e.members)
		w.str(e.title)
	}
	r.send(tok, w.b)
}

func (r *relay) doReport(tok string, ms int64) {
	p := r.of(tok)
	if p == nil || p.level(tok) != manage {
		return
	}
	p.mgrPos[tok] = mgrReport{pos: ms, at: time.Now()}
	r.driftSeek(p)
}

func (r *relay) driftSeek(p *party) {
	now := time.Now()
	var vals []int64
	for tok, rep := range p.mgrPos {
		if p.members[tok] != manage || now.Sub(rep.at) > 15*time.Second {
			delete(p.mgrPos, tok)
			continue
		}
		pos := rep.pos
		if !p.paused {
			pos += now.Sub(rep.at).Milliseconds()
		}
		vals = append(vals, pos)
	}
	if len(vals) == 0 {
		return
	}
	slices.Sort(vals)
	n := len(vals)
	med := vals[n/2]
	if n%2 == 0 {
		med = (vals[n/2-1] + vals[n/2]) / 2
	}
	w := &wtr{}
	w.u8(sSeek)
	w.i64(med)
	for m := range p.members {
		r.send(m, w.b)
	}
}

func (r *relay) doSetSponsorBlock(tok string, flags byte) {
	p := r.of(tok)
	if p == nil || p.level(tok) != manage {
		return
	}
	p.sbFlags = flags & 0x0F
	slog.Info("set sponsorblock", "party", p.id, "flags", p.sbFlags)
	r.broadcast(p)
}

func (r *relay) doSetRepeat(tok string, on bool) {
	p := r.of(tok)
	if p == nil || p.level(tok) != manage {
		return
	}
	p.repeatOne = on
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
		uri := rd.blob()
		title := capBytes(rd.blob(), 200)
		if rd.bad || len(uri) == 0 || len(uri) > 1000 {
			return
		}
		if len(p.tracks) >= 500 {
			return
		}
		p.tracks = append(p.tracks, [3][]byte{uri, title, []byte(r.nameOf(tok))})
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
		clear(p.mgrPos)
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
		clear(p.mgrPos)
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
	g := gcm(deriveSession(r.key, msg1, msg2, ssx, ssm))
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
	newToken := token == "" || r.tokens[token].IsZero()
	if newToken {
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
			slog.Warn("flood", "name", name)
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
	slog.Info("disconnect", "name", name)
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

// sweepTokens expires idle tokens that have not reconnected within tokenTTL.
// Stops when ctx is cancelled (on relay shutdown).
func (r *relay) sweepTokens(ctx context.Context) {
	t := time.NewTicker(sweepEvery)
	defer t.Stop()
	for {
		select {
		case <-t.C:
			now := time.Now()
			r.mu.Lock()
			for tok, seen := range r.tokens {
				if r.conns[tok] == nil && now.Sub(seen) > tokenTTL {
					delete(r.tokens, tok)
				}
			}
			r.mu.Unlock()
		case <-ctx.Done():
			return
		}
	}
}

// genSecret generates an n-character random string using rejection sampling
// to eliminate modulo bias (charset length 57 doesn't divide 256 evenly).
func genSecret(n int) string {
	const charset = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
	const accept = 256 - 256%len(charset) // 228 — values 228..255 rejected
	out := make([]byte, n)
	buf := make([]byte, n+64)
	done := 0
	for done < n {
		if _, err := rand.Read(buf); err != nil {
			log.Fatal(err)
		}
		for _, v := range buf {
			if int(v) < accept {
				out[done] = charset[int(v)%len(charset)]
				done++
				if done == n {
					return string(out)
				}
			}
		}
	}
	return string(out)
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
	slog.Info("listening", "host", host, "port", port)
	go r.sweepTokens(ctx)
	go func() { <-ctx.Done(); ln.Close() }()
	for {
		c, err := ln.Accept()
		if err != nil {
			if ctx.Err() != nil {
				slog.Info("shutdown")
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
