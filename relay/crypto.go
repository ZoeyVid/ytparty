package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/binary"
)

const kdfSalt = "ytparty-relay-v1"
const kdfIter = 600000
const skLabel = "ytparty-sk-v2"

func pbkdf2(password, salt []byte, iter, keyLen int) []byte {
	prf := hmac.New(sha256.New, password)
	hLen := prf.Size()
	numBlocks := (keyLen + hLen - 1) / hLen
	var block [4]byte
	dk := make([]byte, 0, numBlocks*hLen)
	u := make([]byte, hLen)
	for n := 1; n <= numBlocks; n++ {
		prf.Reset()
		prf.Write(salt)
		binary.BigEndian.PutUint32(block[:], uint32(n))
		prf.Write(block[:])
		dk = prf.Sum(dk)
		t := dk[len(dk)-hLen:]
		copy(u, t)
		for i := 2; i <= iter; i++ {
			prf.Reset()
			prf.Write(u)
			u = prf.Sum(u[:0])
			for x := range t {
				t[x] ^= u[x]
			}
		}
	}
	return dk[:keyLen]
}

func deriveKey(password string) []byte {
	return pbkdf2([]byte(password), []byte(kdfSalt), kdfIter, 32)
}

func deriveSession(k, msg1, msg2, ssx, ssm []byte) []byte {
	h := hmac.New(sha256.New, k)
	h.Write([]byte(skLabel))
	h.Write(msg1)
	h.Write(msg2)
	h.Write(ssx)
	h.Write(ssm)
	return h.Sum(nil)
}

func nonce(dir byte, ctr uint64) []byte {
	n := make([]byte, 12)
	n[0] = dir
	binary.BigEndian.PutUint64(n[4:], ctr)
	return n
}

func gcm(sk []byte) cipher.AEAD {
	blk, _ := aes.NewCipher(sk)
	g, _ := cipher.NewGCM(blk)
	return g
}
