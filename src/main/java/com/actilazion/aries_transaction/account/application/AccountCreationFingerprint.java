package com.actilazion.aries_transaction.account.application;

import com.actilazion.aries_transaction.account.dto.CreateAccountRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class AccountCreationFingerprint {
    private static final String DOMAIN = "ACCOUNT_CREATE_V1";

    private AccountCreationFingerprint() {
    }

    public static String hash(CreateAccountRequest request) {
        String canonical = canonicalPart(DOMAIN)
                + canonicalPart(request.accountType().name())
                + canonicalPart(request.currency())
                + canonicalPart(request.description());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static String canonicalPart(String value) {
        if (value == null) {
            return "-1:";
        }
        return value.getBytes(StandardCharsets.UTF_8).length + ":" + value;
    }
}
