package com.actilazion.aries_transaction.smartotp.domain;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;

/** RFC 6287: fixed Aries profile; Q is SHA-256 of the exact server payload bytes. */
public final class Ocra {
    public static final String SUITE = "OCRA-1:HOTP-SHA256-8:QH64";
    private Ocra() {}

    public static String generate(byte[] secret, byte[] payload) {
        if (secret.length != 32) throw new IllegalArgumentException("Expected a 256-bit OCRA key");
        try {
            return response(SUITE, "HmacSHA256", 8, secret,
                    MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("OCRA unavailable", ex);
        }
    }

    // Package-visible primitive also permits published RFC known-answer tests.
    static String response(String suite, String algorithm, int digits, byte[] secret, byte[] question)
            throws GeneralSecurityException {
        if (question.length > 128) throw new IllegalArgumentException("Question too long");
        byte[] prefix = suite.getBytes(StandardCharsets.US_ASCII);
        byte[] input = Arrays.copyOf(prefix, prefix.length + 1 + 128);
        System.arraycopy(question, 0, input, prefix.length + 1, question.length);
        Mac mac = Mac.getInstance(algorithm);
        mac.init(new SecretKeySpec(secret, algorithm));
        byte[] result = mac.doFinal(input);
        int offset = result[result.length - 1] & 15;
        int binary = ((result[offset] & 127) << 24) | ((result[offset + 1] & 255) << 16)
                | ((result[offset + 2] & 255) << 8) | (result[offset + 3] & 255);
        int modulus = digits == 8 ? 100_000_000 : 1_000_000;
        return String.format(Locale.ROOT, "%0" + digits + "d", binary % modulus);
    }

    public static boolean matches(byte[] secret, byte[] payload, String code) {
        return code != null && code.matches("[0-9]{8}") && MessageDigest.isEqual(
                generate(secret, payload).getBytes(StandardCharsets.US_ASCII), code.getBytes(StandardCharsets.US_ASCII));
    }
}
