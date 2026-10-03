package main

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/binary"
	"math/bits"
)

func mac(key []byte, parts ...[]byte) []byte {
	h := hmac.New(sha256.New, key)
	for _, p := range parts {
		h.Write(p)
	}
	return h.Sum(nil)
}

func nonce(dir byte, ctr uint64) []byte {
	n := make([]byte, 12)
	n[0] = dir
	binary.BigEndian.PutUint64(n[4:], ctr)
	return n
}

func gcm(sk []byte) (cipher.AEAD, error) {
	blk, err := aes.NewCipher(sk)
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(blk)
}

func pad(b []byte) []byte {
	p := make([]byte, min(max(256, 1<<bits.Len(uint(len(b)))), maxData+1))
	copy(p, b)
	p[len(b)] = 0x80
	return p
}

func unpad(b []byte) ([]byte, bool) {
	t := bytes.TrimRight(b, "\x00")
	if len(t) == 0 || t[len(t)-1] != 0x80 {
		return nil, false
	}
	return t[:len(t)-1], true
}
