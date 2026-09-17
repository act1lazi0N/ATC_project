package com.actilazion.aries_transaction.smartotp.application;

import com.actilazion.aries_transaction.smartotp.domain.SmartOtpException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

@Component
@RequiredArgsConstructor
public class SmartOtpCrypto {
    private final SmartOtpProperties properties;
    private final SecureRandom random = new SecureRandom();

    @PostConstruct
    void validateConfiguration() {
        if (properties.getMode() != SmartOtpProperties.Mode.DISABLED) key();
    }

    public byte[] randomBytes(int size) { byte[] bytes = new byte[size]; random.nextBytes(bytes); return bytes; }
    public String token() { return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32)); }
    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) { throw new IllegalStateException(ex); }
    }
    public String encrypt(UUID user, UUID credential, byte[] secret) {
        try {
            byte[] nonce = randomBytes(12);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, user, credential, nonce);
            byte[] encrypted = cipher.doFinal(secret);
            byte[] result = Arrays.copyOf(nonce, nonce.length + encrypted.length);
            System.arraycopy(encrypted, 0, result, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(result);
        } catch (GeneralSecurityException ex) { throw SmartOtpException.unavailable(); }
    }
    public byte[] decrypt(UUID user, UUID credential, String keyId, String ciphertext) {
        if (!properties.getKeyId().equals(keyId)) throw SmartOtpException.unavailable();
        try {
            byte[] bytes = Base64.getDecoder().decode(ciphertext);
            if (bytes.length != 60) throw SmartOtpException.unavailable();
            return cipher(Cipher.DECRYPT_MODE, user, credential, Arrays.copyOf(bytes, 12))
                    .doFinal(Arrays.copyOfRange(bytes, 12, bytes.length));
        } catch (GeneralSecurityException | IllegalArgumentException ex) { throw SmartOtpException.unavailable(); }
    }
    private Cipher cipher(int mode, UUID user, UUID credential, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(("aries-smart-otp:v1:" + user + ":" + credential).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
    private byte[] key() {
        try {
            byte[] key = Base64.getDecoder().decode(properties.getEncryptionKey());
            if (key.length != 32 || !properties.getKeyId().matches("[A-Za-z0-9_-]{1,64}")) throw SmartOtpException.unavailable();
            return key;
        } catch (IllegalArgumentException ex) { throw SmartOtpException.unavailable(); }
    }
}
