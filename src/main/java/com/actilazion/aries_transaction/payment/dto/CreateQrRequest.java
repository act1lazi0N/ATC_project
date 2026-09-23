package com.actilazion.aries_transaction.payment.dto;

import com.actilazion.aries_transaction.payment.domain.QrType;
import jakarta.validation.constraints.*;

public record CreateQrRequest(
        @NotNull QrType type,
        @Size(max = 64) @Pattern(regexp = "^\\d{1,16}(\\.\\d+)?$") String amount,
        @NotBlank @Pattern(regexp = "VND") String currency,
        @Size(max = 255) String description
) {}
