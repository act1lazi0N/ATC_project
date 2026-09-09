package com.actilazion.aries_transaction.support;

import java.security.SecureRandom;
import java.util.Base64;

/** Disposable key material generated in memory; never shared with application deployments. */
public final class TestSecrets {
    private static final SecureRandom RANDOM = new SecureRandom();

    private TestSecrets() {
    }

    public static String newBase64Key() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
