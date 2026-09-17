package com.actilazion.aries_transaction.smartotp.domain;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class OcraTest {
    @Test void rfc6287Sha256KnownAnswers() throws Exception {
        byte[] key = "12345678901234567890123456789012".getBytes(StandardCharsets.US_ASCII);
        String[] expected = {"28247970", "01984843", "65387857", "03351211", "83412541"};
        for (int i = 0; i < expected.length; i++) {
            byte[] q = ("CLI2222" + i + "SRV1111" + i).getBytes(StandardCharsets.US_ASCII);
            assertThat(Ocra.response("OCRA-1:HOTP-SHA256-8:QA08", "HmacSHA256", 8, key, q)).isEqualTo(expected[i]);
        }
    }
    @Test void ariesProfileRejectsChangedBytesAndMalformedCodes() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) i;
        byte[] payload = "{\"version\":1,\"purpose\":\"TRANSFER\",\"amount\":\"1500.00\"}".getBytes(StandardCharsets.UTF_8);
        // Independently generated with .NET SHA256/HMACSHA256, not the Java implementation.
        assertThat(Ocra.generate(key, payload)).isEqualTo("29746856");
        String code = Ocra.generate(key, payload);
        assertThat(Ocra.matches(key, payload, code)).isTrue();
        payload[0] ^= 1;
        assertThat(Ocra.matches(key, payload, code)).isFalse();
        assertThat(Ocra.matches(key, payload, "1234567")).isFalse();
        assertThat(Ocra.matches(key, payload, null)).isFalse();
    }
}
