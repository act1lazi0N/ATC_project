package com.actilazion.aries_transaction.account;

import com.actilazion.aries_transaction.account.dto.CreateAccountRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreateAccountRequestDeserializerTest {
    @Test
    void rejectsUnknownFieldsWithoutChangingOtherDtoRules() {
        ObjectMapper mapper = new ObjectMapper();

        assertThatThrownBy(() -> mapper.readValue(
                "{\"accountType\":\"PERSONAL\",\"currency\":\"VND\","
                        + "\"idempotencyKey\":\"account-key-0001\",\"balance\":\"10\"}",
                CreateAccountRequest.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class);
    }
}
