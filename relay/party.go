package main

import (
	"slices"
	"strings"
)

const (
	listen = 0
	invite = 1
	manage = 2
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

type party struct {
	id          string
	members     map[string]int
	invites     map[string]int
	tracks      []trackRef
	isPublic    bool
	pubJoinLvl  int
	curIndex    int
	paused      bool
	autoRemove  bool
	sbFlags     byte
	repeatOne   bool
	nextTrackId int
	generation  int
	trackStart  int64
	pausedAccum int64
	pausedSince int64
}

type trackRef struct {
	id    int
	uri   []byte
	title []byte
	reqr  []byte
}

func (p *party) curTrackID() int {
	if p.curIndex >= 0 && p.curIndex < len(p.tracks) {
		return p.tracks[p.curIndex].id
	}
	return 0
}

func (p *party) indexOf(id int) int {
	for i := range p.tracks {
		if p.tracks[i].id == id {
			return i
		}
	}
	return -1
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
