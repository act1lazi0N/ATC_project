package com.actilazion.aries_transaction.webhook.application;

import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.transaction.domain.Transaction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Component
public class WebhookMerchantAudienceResolver {
    public Set<UUID> resolve(Transaction transaction) {
        Set<UUID> merchantIds = new LinkedHashSet<>();
        addMerchantOwner(merchantIds, transaction.getFromAccount());
        addMerchantOwner(merchantIds, transaction.getToAccount());
        return Set.copyOf(merchantIds);
    }

    private void addMerchantOwner(Set<UUID> merchantIds, Account account) {
        User owner = account == null ? null : account.getUser();
        if (owner != null && owner.getId() != null && owner.getRole() == Role.MERCHANT) {
            merchantIds.add(owner.getId());
        }
    }
}
