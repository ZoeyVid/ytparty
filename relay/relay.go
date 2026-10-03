package main

import (
	"crypto/ecdh"
	"crypto/rand"
	"log"
	"log/slog"
	"strings"
	"sync"
)

type relay struct {
	mu            sync.Mutex
	priv          *ecdh.PrivateKey
	pub           []byte
	byID          map[string]*party
	playerToParty map[string]string
	conns         map[string]*conn
	tokens        map[string]tokenInfo
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
	p := &party{id: r.newID(), members: map[string]int{tok: manage}, invites: map[string]int{}, failed: map[string]bool{}, curIndex: -1, autoRemove: true, sbFlags: 0x0F, nextTrackId: 1}
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
	delete(p.failed, tok)
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

func (r *relay) identityInParty(p *party, uuid, name string) bool {
	name = strings.ToLower(name)
	for tok := range p.members {
		if r.uuidOf(tok) == uuid || strings.ToLower(r.nameOf(tok)) == name {
			return true
		}
	}
	return false
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
	frames := [][]byte{payload}
	if len(payload) > maxData {
		frames = nil
		for off, n := 0, maxData-9; off < len(payload); off += n {
			w := &wtr{}
			w.u8(sPart)
			w.i32(off)
			w.i32(len(payload))
			w.b = append(w.b, payload[off:min(off+n, len(payload))]...)
			frames = append(frames, w.b)
		}
	}
	for _, f := range frames {
		select {
		case c.out <- f:
		default:
			c.stop()
			return
		}
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
		res.p.skipFailed()
		r.broadcast(res.p)
	}
}
func (r *relay) msg(tok, text string) {
	w := &wtr{}
	w.u8(sMessage)
	w.str(text)
	r.send(tok, w.b)
}

func genSecret(n int) string {
	const charset = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
	const accept = 256 - 256%len(charset)
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
