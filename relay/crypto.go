package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/pbkdf2"
	"crypto/sha256"
	"encoding/binary"
	"log"
)

const kdfSalt = "ytparty-relay-v1"
const kdfIter = 600000
const skLabel = "ytparty-sk-v2"

func deriveKey(password string) []byte {
	k, err := pbkdf2.Key(sha256.New, password, []byte(kdfSalt), kdfIter, 32)
	if err != nil {
		log.Fatal(err)
	}
	return k
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
