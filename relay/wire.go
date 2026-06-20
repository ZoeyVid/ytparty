package main

import (
	"encoding/binary"
	"io"
	"net"
	"slices"
	"time"
)

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
	return slices.Clone(v)
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
