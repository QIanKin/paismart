package com.yizhaoqi.smartpai.service.xhs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * XhsCookie 的对称加密工具。
 *
 * 设计：
 *  - 使用 AES/GCM/NoPadding（认证加密，防篡改）
 *  - 密钥来自配置 `xhs.cookie-secret`；通过 SHA-256 派生 256-bit key（运维可填任意长度明文）
 *  - 密文格式：base64( IV[12] || ciphertext || tag )，GCM 会把 tag 自动附到 ciphertext 尾部
 *  - 默认 secret = "bee-xhs-dev-only-CHANGE-ME"；生产必须覆盖，否则记日志告警
 *
 * 线程安全：SecretKey 本身不可变，SecureRandom 在 JDK 内部同步，Cipher 每次 new 一把。
 */
@Component
public class CookieCipher {

    private static final Logger log = LoggerFactory.getLogger(CookieCipher.class);
    private static final String CIPHER_SPEC = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final String DEFAULT_SECRET = "bee-xhs-dev-only-CHANGE-ME";

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();
    private final boolean usingDefault;

    public CookieCipher(@Value("${xhs.cookie-secret:}") String raw) {
        String secret = raw == null || raw.isBlank() ? DEFAULT_SECRET : raw;
        this.usingDefault = DEFAULT_SECRET.equals(secret);
        if (usingDefault) {
            log.warn("xhs.cookie-secret 未设置，使用默认值。生产部署请在环境变量 XHS_COOKIE_SECRET 中覆盖！");
        }
        try {
            byte[] sha = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(sha, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("无法初始化 CookieCipher", e);
        }
    }

    public String encrypt(String plain) {
        if (plain == null) return null;
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance(CIPHER_SPEC);
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] body = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + body.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(body, 0, out, iv.length, body.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("cookie 加密失败", e);
        }
    }

    public String decrypt(String base64) {
        if (base64 == null || base64.isBlank()) return null;
        try {
            byte[] in = Base64.getDecoder().decode(base64);
            if (in.length <= IV_LENGTH) throw new IllegalArgumentException("密文过短");
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(in, 0, iv, 0, IV_LENGTH);
            byte[] body = new byte[in.length - IV_LENGTH];
            System.arraycopy(in, IV_LENGTH, body, 0, body.length);
            Cipher c = Cipher.getInstance(CIPHER_SPEC);
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(c.doFinal(body), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("cookie 解密失败（可能 xhs.cookie-secret 改过？）", e);
        }
    }

    /** 脱敏预览：前 16 + "..." + 后 8。用于前端列表。 */
    public String preview(String plain) {
        if (plain == null) return null;
        String s = plain.replaceAll("\\s+", "");
        if (s.length() <= 28) return s;
        return s.substring(0, 16) + "..." + s.substring(s.length() - 8);
    }

    public boolean isUsingDefaultSecret() {
        return usingDefault;
    }
}
