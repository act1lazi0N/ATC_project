package com.actilazion.aries_transaction.payment.domain;

import java.util.UUID;
import java.util.regex.Pattern;

public final class QrPayload {
    private static final String PREFIX = "aries:pay:v1:";
    private static final Pattern FORMAT = Pattern.compile(
            "aries:pay:v1:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    private QrPayload() {}

    public static UUID parse(String payload) {
        if (payload == null || payload.length() > 256 || !FORMAT.matcher(payload).matches()) {
            throw new IllegalArgumentException("Invalid Aries v1 QR payload");
        }
        return UUID.fromString(payload.substring(PREFIX.length()));
    }

    public static String encode(UUID id) { return PREFIX + id; }
}
