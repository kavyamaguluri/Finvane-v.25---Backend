package com.finvane.utils;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Component
public class CryptoUtility {

    private static final String DEFAULT_KEY = "change_this_to_a_32_byte_hex_key_0000000000000000";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128; // in bits (16 bytes)
    private static final SecureRandom secureRandom = new SecureRandom();

    private static byte[] getKeyBytes() {
        String envKey = System.getenv("ENCRYPTION_KEY");
        if (envKey == null || envKey.isBlank()) {
            envKey = DEFAULT_KEY;
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return sha.digest(envKey.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive encryption key", e);
        }
    }

    public static String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            return null;
        }
        try {
            byte[] keyBytes = getKeyBytes();
            SecretKey secretKey = new SecretKeySpec(keyBytes, "AES");

            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] cipherTextWithTag = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            int cipherTextLen = cipherTextWithTag.length - 16;
            byte[] cipherText = Arrays.copyOfRange(cipherTextWithTag, 0, cipherTextLen);
            byte[] authTag = Arrays.copyOfRange(cipherTextWithTag, cipherTextLen, cipherTextWithTag.length);

            String ivB64 = Base64.getEncoder().encodeToString(iv);
            String tagB64 = Base64.getEncoder().encodeToString(authTag);
            String dataB64 = Base64.getEncoder().encodeToString(cipherText);

            return ivB64 + ":" + tagB64 + ":" + dataB64;
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    public static String decrypt(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            String[] parts = payload.split(":");
            if (parts.length != 3) {
                return "[decryption error]";
            }
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] authTag = Base64.getDecoder().decode(parts[1]);
            byte[] cipherText = Base64.getDecoder().decode(parts[2]);

            byte[] cipherTextWithTag = new byte[cipherText.length + authTag.length];
            System.arraycopy(cipherText, 0, cipherTextWithTag, 0, cipherText.length);
            System.arraycopy(authTag, 0, cipherTextWithTag, cipherText.length, authTag.length);

            byte[] keyBytes = getKeyBytes();
            SecretKey secretKey = new SecretKeySpec(keyBytes, "AES");

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] plainBytes = cipher.doFinal(cipherTextWithTag);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "[decryption error]";
        }
    }

    public static String mask(String value) {
        return mask(value, 4);
    }

    public static String mask(String value, int visible) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() <= visible) {
            return "*".repeat(value.length());
        }
        return "*".repeat(value.length() - visible) + value.substring(value.length() - visible);
    }
}

