package com.actilazion.aries_transaction.account.application;

import com.actilazion.aries_transaction.account.domain.AccountStatus;
import com.actilazion.aries_transaction.account.domain.AccountType;
import com.actilazion.aries_transaction.account.dto.AccountResponse;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class AccountCreationResponseSnapshot {
    private AccountCreationResponseSnapshot() {
    }

    public static Map<String, Object> toPayload(AccountResponse response) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", response.id().toString());
        payload.put("userId", response.userId().toString());
        payload.put("accountNumber", response.accountNumber());
        payload.put("accountType", response.accountType().name());
        payload.put("balance", response.balance().toPlainString());
        payload.put("currency", response.currency());
        payload.put("status", response.status().name());
        payload.put("createdAt", response.createdAt() == null ? null : response.createdAt().toString());
        payload.put("description", response.description());
        return payload;
    }

    public static AccountResponse fromPayload(Map<String, Object> payload) {
        return new AccountResponse(
                UUID.fromString((String) payload.get("id")),
                UUID.fromString((String) payload.get("userId")),
                (String) payload.get("accountNumber"),
                AccountType.valueOf((String) payload.get("accountType")),
                new BigDecimal((String) payload.get("balance")),
                (String) payload.get("currency"),
                AccountStatus.valueOf((String) payload.get("status")),
                payload.get("createdAt") == null ? null : OffsetDateTime.parse((String) payload.get("createdAt")),
                (String) payload.get("description")
        );
    }
}
