package de.zoeyvid.ytparty.relay;

import javax.crypto.Cipher;
import javax.crypto.KEM;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

final class RelayCrypto {
    static final SecureRandom RNG = new SecureRandom();
    static final int MAX_FRAME = 1 << 20;
    static final int MAX_DATA = MAX_FRAME - 16 - 1;
    private static final byte[] X25519_SPKI_HEADER = x25519Header();

    private RelayCrypto() {}

    static byte[] parseKey(String s) {
        try {
            byte[] k = Base64.getUrlDecoder().decode(s.replaceAll("\\s", ""));
            return k.length == 32 ? k : null;
        } catch (IllegalArgumentException e) { return null; }
    }

    static byte[] nonce(int dir, long ctr) {
        byte[] n = new byte[12];
        n[0] = (byte) dir;
        for (int i = 0; i < 8; i++) n[11 - i] = (byte) (ctr >>> (8 * i));
        return n;
    }

    static byte[] encrypt(byte[] sk, byte[] nonce, byte[] pt) throws GeneralSecurityException { return gcm(Cipher.ENCRYPT_MODE, sk, nonce, pt); }
    static byte[] decrypt(byte[] sk, byte[] nonce, byte[] ct) throws GeneralSecurityException { return gcm(Cipher.DECRYPT_MODE, sk, nonce, ct); }

    static KeyPair genX25519() throws GeneralSecurityException { return KeyPairGenerator.getInstance("X25519").generateKeyPair(); }
    static KeyPair genMlKem() throws GeneralSecurityException { return KeyPairGenerator.getInstance("ML-KEM-768").generateKeyPair(); }
    static byte[] x25519Pub(KeyPair kp) { return tail(kp.getPublic().getEncoded(), 32); }
    static byte[] mlkemEk(KeyPair kp) { return tail(kp.getPublic().getEncoded(), 1184); }

    static byte[] x25519Agree(PrivateKey priv, byte[] peerRaw32) throws GeneralSecurityException {
        byte[] spki = new byte[X25519_SPKI_HEADER.length + 32];
        System.arraycopy(X25519_SPKI_HEADER, 0, spki, 0, X25519_SPKI_HEADER.length);
        System.arraycopy(peerRaw32, 0, spki, X25519_SPKI_HEADER.length, 32);
        PublicKey peer = KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(spki));
        KeyAgreement ka = KeyAgreement.getInstance("X25519");
        ka.init(priv);
        ka.doPhase(peer, true);
        return ka.generateSecret();
    }

    static byte[] mlkemDecapsulate(PrivateKey priv, byte[] ct) throws GeneralSecurityException {
        return KEM.getInstance("ML-KEM").newDecapsulator(priv).decapsulate(ct).getEncoded();
    }

    static byte[] pad(byte[] b) {
        byte[] p = Arrays.copyOf(b, Math.min(Math.max(256, Integer.highestOneBit(b.length) << 1), MAX_DATA + 1));
        p[b.length] = (byte) 0x80;
        return p;
    }

    static byte[] unpad(byte[] b) throws GeneralSecurityException {
        int i = b.length - 1;
        while (i >= 0 && b[i] == 0) i--;
        if (i < 0 || b[i] != (byte) 0x80) throw new GeneralSecurityException("bad padding");
        return Arrays.copyOf(b, i);
    }

    private static byte[] tail(byte[] b, int n) { return Arrays.copyOfRange(b, b.length - n, b.length); }

    private static byte[] x25519Header() {
        try {
            byte[] enc = KeyPairGenerator.getInstance("X25519").generateKeyPair().getPublic().getEncoded();
            return Arrays.copyOf(enc, enc.length - 32);
        } catch (GeneralSecurityException e) { throw new RuntimeException(e); }
    }

    private static byte[] gcm(int mode, byte[] sk, byte[] nonce, byte[] in) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, new SecretKeySpec(sk, "AES"), new GCMParameterSpec(128, nonce));
        return c.doFinal(in);
    }

    static byte[] hmac(byte[] key, byte[]... parts) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            for (byte[] p : parts) m.update(p);
            return m.doFinal();
        } catch (GeneralSecurityException e) { throw new RuntimeException(e); }
    }
}
