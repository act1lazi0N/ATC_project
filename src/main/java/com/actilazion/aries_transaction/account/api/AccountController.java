package com.actilazion.aries_transaction.account.api;

import com.actilazion.aries_transaction.account.application.AccountService;
import com.actilazion.aries_transaction.account.dto.AccountResponse;
import com.actilazion.aries_transaction.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
import com.actilazion.aries_transaction.account.dto.CreateAccountRequest;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
@Tag(name = "Accounts", description = "Authenticated account operations")
@SecurityRequirement(name = "bearerAuth")
public class AccountController {
    private final AccountService accountService;

    @PostMapping
    @Operation(summary = "Create an authenticated user's financial account")
    public ResponseEntity<ApiResponse<AccountResponse>> create(
            @Valid @RequestBody CreateAccountRequest request,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        return ResponseEntity.ok(ApiResponse.ok("Account created successfully",
                accountService.create(request, userDetails.getUsername())));
    }

    @GetMapping
    @Operation(summary = "List the authenticated user's accounts")
    public ResponseEntity<ApiResponse<List<AccountResponse>>> getMyAccounts(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Accounts retrieved successfully",
                accountService.getMyAccounts(userDetails.getUsername())
        ));
    }
}
