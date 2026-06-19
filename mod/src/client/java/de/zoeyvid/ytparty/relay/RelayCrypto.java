package de.zoeyvid.ytparty.relay;

import javax.crypto.Cipher;
import javax.crypto.KEM;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

final class RelayCrypto {
    static final SecureRandom RNG = new SecureRandom();
    private static final byte[] SALT = "ytparty-relay-v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SK_LABEL = "ytparty-sk-v2".getBytes(StandardCharsets.UTF_8);
    private static final byte[] X25519_SPKI_HEADER = x25519Header();

    private RelayCrypto() {}

    static byte[] deriveKey(String password) {
        return pbkdf2(password.getBytes(StandardCharsets.UTF_8), SALT, 600000, 32);
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

    static byte[] deriveSession(byte[] k, byte[] msg1, byte[] msg2, byte[] ssx, byte[] ssm) {
        return hmac(k, SK_LABEL, msg1, msg2, ssx, ssm);
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

    private static byte[] hmac(byte[] key, byte[]... parts) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            for (byte[] p : parts) m.update(p);
            return m.doFinal();
        } catch (GeneralSecurityException e) { throw new RuntimeException(e); }
    }

    private static byte[] pbkdf2(byte[] pw, byte[] salt, int iter, int dkLen) {
        try {
            Mac prf = Mac.getInstance("HmacSHA256");
            prf.init(new SecretKeySpec(pw, "HmacSHA256"));
            int hLen = prf.getMacLength(), blocks = (dkLen + hLen - 1) / hLen;
            byte[] dk = new byte[blocks * hLen], block = new byte[4];
            for (int b = 1; b <= blocks; b++) {
                prf.reset();
                prf.update(salt);
                block[0] = (byte) (b >>> 24); block[1] = (byte) (b >>> 16); block[2] = (byte) (b >>> 8); block[3] = (byte) b;
                prf.update(block);
                byte[] u = prf.doFinal(), t = u.clone();
                for (int i = 2; i <= iter; i++) { prf.reset(); u = prf.doFinal(u); for (int x = 0; x < hLen; x++) t[x] ^= u[x]; }
                System.arraycopy(t, 0, dk, (b - 1) * hLen, hLen);
            }
            byte[] out = new byte[dkLen];
            System.arraycopy(dk, 0, out, 0, dkLen);
            return out;
        } catch (GeneralSecurityException e) { throw new RuntimeException(e); }
    }
}
