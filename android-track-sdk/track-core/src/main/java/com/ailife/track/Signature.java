package com.ailife.track;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 batch signature and optional AES-256-GCM payload encryption
 * (contracts/api.md: sig = HMAC(appKey, ts + "." + sha256(blob))).
 * Key derivation: SHA-256(appKey + "ailife-track-v1") -> 32-byte AES key,
 * random 12-byte IV prepended to the ciphertext. API 21+ ships both AES/GCM
 * and HmacSHA256.
 */
public final class Signature {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Signature() { }

    public static String hmacSha256Hex(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return toHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("hmac unavailable", e);
        }
    }

    public static String signBatch(String appKey, long ts, byte[] blob) {
        return hmacSha256Hex(appKey, ts + "." + sha256Hex(blob));
    }

    /** Constant-time signature comparison (anti-tamper). */
    public static boolean safeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    public static String sha256Hex(byte[] data) {
        try {
            return toHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("sha256 unavailable", e);
        }
    }

    public static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    public static byte[] deriveAesKey(String appKey) {
        try {
            byte[] seed = (appKey + "ailife-track-v1").getBytes(StandardCharsets.UTF_8);
            return MessageDigest.getInstance("SHA-256").digest(seed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("sha256 unavailable", e);
        }
    }

    /** AES-256-GCM encrypt; returns iv(12) || ciphertext+tag. */
    public static byte[] encrypt(byte[] key, byte[] plain) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, iv));
            byte[] ct = cipher.doFinal(plain);
            byte[] out = new byte[12 + ct.length];
            System.arraycopy(iv, 0, out, 0, 12);
            System.arraycopy(ct, 0, out, 12, ct.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("encrypt failed", e);
        }
    }

    /** AES-256-GCM decrypt of iv(12) || ciphertext+tag; null on any failure. */
    public static byte[] decrypt(byte[] key, byte[] sealed) {
        try {
            if (sealed == null || sealed.length <= 12) {
                return null;
            }
            byte[] iv = new byte[12];
            System.arraycopy(sealed, 0, iv, 0, 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, iv));
            return cipher.doFinal(sealed, 12, sealed.length - 12);
        } catch (Exception e) {
            return null;
        }
    }
}
