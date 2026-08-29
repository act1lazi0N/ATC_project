package com.actilazion.aries_transaction.account.dto;

import com.actilazion.aries_transaction.account.domain.AccountType;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.deser.std.StdDeserializer;

import java.util.Set;

/** Keeps the account-create boundary strict without changing other request DTOs. */
public final class CreateAccountRequestDeserializer extends StdDeserializer<CreateAccountRequest> {
    private static final Set<String> KNOWN_FIELDS = Set.of(
            "accountType", "currency", "description", "idempotencyKey");

    public CreateAccountRequestDeserializer() {
        super(CreateAccountRequest.class);
    }

    @Override
    public CreateAccountRequest deserialize(JsonParser parser, DeserializationContext context) throws JacksonException {
        JsonNode node = context.readTree(parser);
        if (node == null || !node.isObject()) {
            throw MismatchedInputException.from(
                    parser, CreateAccountRequest.class, "Create account request must be a JSON object");
        }
        for (String field : node.propertyNames()) {
            if (!KNOWN_FIELDS.contains(field)) {
                throw UnrecognizedPropertyException.from(
                        parser, CreateAccountRequest.class, field, new java.util.ArrayList<>(KNOWN_FIELDS));
            }
        }
        return new CreateAccountRequest(
                accountType(node, context, parser),
                text(node, "currency", parser),
                text(node, "description", parser),
                text(node, "idempotencyKey", parser)
        );
    }

    private AccountType accountType(
            JsonNode node,
            DeserializationContext context,
            JsonParser parser
    ) throws JacksonException {
        String value = text(node, "accountType", parser);
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

    private String text(JsonNode node, String field, JsonParser parser) throws JacksonException {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw MismatchedInputException.from(parser, String.class, field + " must be a string");
        }
        return value.stringValue();
    }
}
