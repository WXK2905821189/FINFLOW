package com.finance.system.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AI 密钥静态加密（V28）：AES-256-GCM，密钥 = SHA-256(app.jwt.secret)。
 *
 * <p>密文格式：Base64(12 字节随机 IV || GCM 密文+认证标签)。解密失败（如 JWT secret
 * 轮换后）抛 {@link AiSecretCipherException}，由配置层转为可读的 400 提示——
 * 页面重新保存一次密钥即恢复。明文密钥只存在内存瞬时变量，永不落日志/审计/响应。</p>
 */
@Component
public class AiSecretCipher {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec keySpec;
    private final SecureRandom random = new SecureRandom();

    public AiSecretCipher(@Value("${app.jwt.secret}") String jwtSecret) {
        try {
            byte[] key = MessageDigest.getInstance("SHA-256")
                    .digest((jwtSecret == null ? "" : jwtSecret).getBytes(StandardCharsets.UTF_8));
            this.keySpec = new SecretKeySpec(key, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("AI 密钥加密初始化失败（SHA-256 不可用）", e);
        }
    }

    /** 加密 → Base64(iv || ciphertext+tag)。 */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[IV_LENGTH + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
            System.arraycopy(encrypted, 0, combined, IV_LENGTH, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("AI 密钥加密失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 解密；密文损坏/密钥不匹配抛 {@link AiSecretCipherException}。 */
    public String decrypt(String base64Cipher) {
        try {
            byte[] combined = Base64.getDecoder().decode(base64Cipher);
            if (combined.length <= IV_LENGTH) {
                throw new IllegalArgumentException("ciphertext too short");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec,
                    new GCMParameterSpec(TAG_BITS, combined, 0, IV_LENGTH));
            byte[] plain = cipher.doFinal(combined, IV_LENGTH, combined.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AiSecretCipherException(e);
        }
    }

    /** 解密失败专用：调用方据此给出「重新保存密钥」的可操作提示。 */
    public static class AiSecretCipherException extends RuntimeException {
        public AiSecretCipherException(Throwable cause) {
            super(cause);
        }
    }
}
