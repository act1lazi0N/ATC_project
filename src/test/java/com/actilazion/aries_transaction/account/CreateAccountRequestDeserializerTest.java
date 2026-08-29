package com.actilazion.aries_transaction.account;

import com.actilazion.aries_transaction.account.dto.CreateAccountRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreateAccountRequestDeserializerTest {
    @Test
    void rejectsUnknownFieldsWithoutChangingOtherDtoRules() {
        ObjectMapper mapper = new ObjectMapper();

        assertThatThrownBy(() -> mapper.readValue(
                "{\"accountType\":\"PERSONAL\",\"currency\":\"VND\","
                        + "\"idempotencyKey\":\"account-key-0001\",\"balance\":\"10\"}",
                CreateAccountRequest.class))
                .isInstanceOf(tools.jackson.databind.exc.UnrecognizedPropertyException.class);
    }

    @Test
    void rejectsNonStringKnownFieldsInsteadOfCoercingThem() {
        ObjectMapper mapper = new ObjectMapper();

        assertThatThrownBy(() -> mapper.readValue(
                "{\"accountType\":\"PERSONAL\",\"currency\":\"VND\","
                        + "\"idempotencyKey\":1234567890123456}",
                CreateAccountRequest.class))
                .isInstanceOf(tools.jackson.databind.exc.MismatchedInputException.class)
                .hasMessageContaining("idempotencyKey must be a string");
    }

    @Test
    void readsOnlyTheDocumentedFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        CreateAccountRequest request = mapper.readValue(
                "{\"accountType\":\"BUSINESS\",\"currency\":\"VND\","
                        + "\"description\":\"Operations\",\"idempotencyKey\":\"account-key-0001\"}",
                CreateAccountRequest.class);

        assertThat(request.accountType().name()).isEqualTo("BUSINESS");
        assertThat(request.currency()).isEqualTo("VND");
        assertThat(request.description()).isEqualTo("Operations");
        assertThat(request.idempotencyKey()).isEqualTo("account-key-0001");
    }
}
