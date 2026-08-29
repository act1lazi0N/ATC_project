package com.actilazion.aries_transaction.account.application;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "app.account.creation")
public class AccountCreationPolicyProperties {
    @Min(1)
    private int maxActiveAccountsPerUser = 5;
}
