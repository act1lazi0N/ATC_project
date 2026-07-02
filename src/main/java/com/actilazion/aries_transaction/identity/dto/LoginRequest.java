package com.actilazion.aries_transaction.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "email is required")
        @Email(message = "Invalid email format")
        String email,
        @NotBlank(message = "password is required")
        String password
) { }
