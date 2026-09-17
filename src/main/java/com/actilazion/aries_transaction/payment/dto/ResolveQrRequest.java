package com.actilazion.aries_transaction.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResolveQrRequest(@NotBlank @Size(max = 256) String payload) {}
