package com.actilazion.aries_transaction.account.dto;

import com.actilazion.aries_transaction.account.domain.AccountType;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.util.Iterator;
import java.util.Set;

/** Keeps the account-create boundary strict without changing other request DTOs. */
public final class CreateAccountRequestDeserializer extends StdDeserializer<CreateAccountRequest> {
    private static final Set<String> KNOWN_FIELDS = Set.of(
            "accountType", "currency", "description", "idempotencyKey");

    public CreateAccountRequestDeserializer() {
        super(CreateAccountRequest.class);
    }

    @Override
    public CreateAccountRequest deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonNode node = parser.getCodec().readTree(parser);
        if (node == null || !node.isObject()) {
            throw JsonMappingException.from(parser, "Create account request must be a JSON object");
        }
        Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!KNOWN_FIELDS.contains(field)) {
                throw UnrecognizedPropertyException.from(
                        parser, CreateAccountRequest.class, field, new java.util.ArrayList<>(KNOWN_FIELDS));
            }
        }
        return new CreateAccountRequest(
                accountType(node, context),
                text(node, "currency"),
                text(node, "description"),
                text(node, "idempotencyKey")
        );
    }

    private AccountType accountType(JsonNode node, DeserializationContext context) throws IOException {
        String value = text(node, "accountType");
        if (value == null) {
            return null;
        }
        try {
            return AccountType.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return (AccountType) context.handleWeirdStringValue(
                    AccountType.class, value, "Unknown account type");
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.isTextual() ? value.textValue() : value.asText();
    }
}
