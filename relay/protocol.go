package main

import (
	"cmp"
	"log/slog"
	"slices"
	"strings"
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
	cSetTrack
	cSetPaused
	cSetPosition
	cSetPublic
	cSetAutoRemove
	cListPublic
	cReportPosition
	cSetSponsorBlock
	cSetRepeat
	cTrackEnded
	cSetPlaylist
	cListPlayers
)
const (
	sState = iota
	sInvited
	sMessage
	sLeft
	sSeek
	sPublicList
	sPlayerList
)

func (r *relay) stateTemplate(p *party) ([]byte, int) {
	w := &wtr{}
	w.u8(sState)
	w.str(p.id)
	off := len(w.b)
	w.u8(0)
	w.boolean(p.isPublic)
	w.u8(p.pubJoinLvl)
	w.boolean(p.paused)
	w.i32(p.curIndex)
	w.boolean(p.autoRemove)
	w.u8(int(p.sbFlags))
	w.boolean(p.repeatOne)
	w.i32(p.generation)
	w.i32(len(p.tracks))
	for _, t := range p.tracks {
		w.i32(t.id)
		w.blob(t.uri)
		w.blob(t.title)
		w.blob(t.reqr)
	}
	w.i32(len(p.members))
	type mem struct {
		name  string
		level int
	}
	ms := make([]mem, 0, len(p.members))
	for tok, l := range p.members {
		ms = append(ms, mem{r.nameOf(tok), l})
	}
	slices.SortFunc(ms, func(a, b mem) int {
		if a.level != b.level {
			return cmp.Compare(b.level, a.level)
		}
		return strings.Compare(strings.ToLower(a.name), strings.ToLower(b.name))
	})
	for _, m := range ms {
		w.str(m.name)
		w.u8(m.level)
	}
	return w.b, off
}

func (r *relay) stateBytes(p *party, viewer string) []byte {
	b, off := r.stateTemplate(p)
	b[off] = byte(p.level(viewer))
	return b
}
func (r *relay) broadcast(p *party) {
	tmpl, off := r.stateTemplate(p)
	for tok := range p.members {
		msg := slices.Clone(tmpl)
		msg[off] = byte(p.level(tok))
		r.send(tok, msg)
	}
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
		name := string(rd.blob())
		if name != "" {
			p := r.of(tok)
			if p != nil && p.level(tok) == manage {
				if target := r.memberByName(p, name); target != "" && target != tok {
					w := &wtr{}
					w.u8(sLeft)
					r.send(target, w.b)
					slog.Info("kick", "by", c.name, "target", name, "party", p.id)
					r.afterLeave(r.leave(target))
				}
			}
			return
		}
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
	case cListPlayers:
		r.doListPlayers(tok)
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
	if r.identityInParty(p, r.uuidOf(tok), r.nameOf(tok)) {
		slog.Info("join rejected: identity already in party", "name", r.nameOf(tok), "party", pid)
		r.msg(tok, "You are already in "+pid+" from another connection")
		return
	}
	r.afterLeave(r.leave(tok))
	if jp := r.joinParty(tok, pid); jp != nil {
		slog.Info("join", "name", r.nameOf(tok), "party", pid)
		r.broadcast(jp)
	}
}
func (r *relay) doInvite(tok, name string, level int) {
	p := r.of(tok)
	if p == nil || p.level(tok) < invite {
		return
	}
	want := strings.ToLower(name)
	g := capped(level, p.level(tok))
	w := &wtr{}
	w.u8(sInvited)
	w.str(r.nameOf(tok))
	w.str(p.id)
	w.u8(g)
	sent := false
	for t, c := range r.conns {
		if strings.ToLower(c.name) == want {
			r.send(t, w.b)
			sent = true
		}
	}
	if !sent {
		r.msg(tok, "Player not online: "+name)
		return
	}
	p.invites[want] = g
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
			title = string(p.tracks[p.curIndex].title)
		}
		pub = append(pub, entry{p.id, len(p.members), title})
	}
	slices.SortFunc(pub, func(a, b entry) int { return cmp.Compare(b.members, a.members) })
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

func (r *relay) doListPlayers(tok string) {
	p := r.of(tok)
	if p == nil || p.level(tok) < invite {
		return
	}
	seen := map[string]string{}
	for _, cc := range r.conns {
		seen[strings.ToLower(cc.name)] = cc.name
	}
	w := &wtr{}
	w.u8(sPlayerList)
	w.i32(len(seen))
	for _, name := range seen {
		w.str(name)
	}
	r.send(tok, w.b)
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
	oldCur := p.curTrackID()
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
		p.tracks = append(p.tracks, trackRef{id: p.nextTrackId, uri: uri, title: title, reqr: []byte(r.nameOf(tok))})
		p.nextTrackId++
		if p.curIndex < 0 {
			p.curIndex = 0
		}
	case cRemove:
		id := rd.i32()
		if rd.bad {
			return
		}
		i := p.indexOf(id)
		if i >= 0 {
			p.tracks = slices.Delete(p.tracks, i, i+1)
			if i < p.curIndex {
				p.curIndex--
			}
			p.curIndex = min(p.curIndex, len(p.tracks)-1)
		}
	case cMove:
		id := rd.i32()
		to := rd.i32()
		if rd.bad {
			return
		}
		from := p.indexOf(id)
		if from >= 0 {
			to = max(0, min(to, len(p.tracks)-1))
			p.move(from, to)
			if from == p.curIndex {
				p.curIndex = to
			} else if from < p.curIndex && to >= p.curIndex {
				p.curIndex--
			} else if from > p.curIndex && to <= p.curIndex {
				p.curIndex++
			}
		}
	case cSetTrack:
		id := rd.i32()
		if rd.bad {
			return
		}
		i := p.indexOf(id)
		if i < 0 {
			return
		}
		p.curIndex = i
		p.paused = false
	case cTrackEnded:
		gen := rd.i32()
		if rd.bad || gen != p.generation || p.curIndex < 0 {
			return
		}
		if p.autoRemove && p.curIndex < len(p.tracks) {
			cur := p.curIndex
			p.tracks = slices.Delete(p.tracks, cur, cur+1)
			p.curIndex = min(cur, len(p.tracks)-1)
		} else {
			p.curIndex = min(p.curIndex+1, len(p.tracks)-1)
			if p.curIndex < 0 {
				p.curIndex = -1
			}
		}
	case cSetPlaylist:
		n := rd.i32()
		if rd.bad || n < 0 || n > 500 {
			return
		}
		nt := make([]trackRef, 0, n)
		for range n {
			uri := rd.blob()
			title := capBytes(rd.blob(), 200)
			if rd.bad {
				return
			}
			if len(uri) == 0 || len(uri) > 1000 {
				continue
			}
			nt = append(nt, trackRef{id: p.nextTrackId, uri: uri, title: title, reqr: []byte(r.nameOf(tok))})
			p.nextTrackId++
		}
		p.tracks = nt
		if len(p.tracks) == 0 {
			p.curIndex = -1
		} else {
			p.curIndex = 0
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
	case cSetSponsorBlock:
		flags := rd.u8()
		if rd.bad {
			return
		}
		p.sbFlags = byte(flags) & 0x0F
	case cSetRepeat:
		on := rd.boolean()
		if rd.bad {
			return
		}
		p.repeatOne = on
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
	if p.curTrackID() != oldCur {
		p.generation++
	}
	r.broadcast(p)
}
