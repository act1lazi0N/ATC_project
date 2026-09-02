package com.actilazion.aries_transaction.operations;

import com.actilazion.aries_transaction.config.JwtService;
import com.actilazion.aries_transaction.identity.application.AuthenticatedUserPrincipal;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest
@ActiveProfiles("test")
class OperationsSecurityMockMvcTest {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired JwtService jwtService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void operationsReadsAllowStaffAndRejectCustomerRoles() throws Exception {
        String operator = token(user(Role.OPERATOR));
        String customer = token(user(Role.USER));

        for (String path : new String[] {
                "/api/v1/operations/overview?range=24h",
                "/api/v1/operations/customers?page=0&size=10",
                "/api/v1/operations/transactions?page=0&size=10",
                "/api/v1/operations/ledger-entries?limit=10"
        }) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + operator))
                    .andExpect(status().isOk());
            mockMvc.perform(get(path).header("Authorization", "Bearer " + customer))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void merchantOverviewAllowsMerchantAndRejectsOrdinaryUser() throws Exception {
        String merchant = token(user(Role.MERCHANT));
        String customer = token(user(Role.USER));

        mockMvc.perform(get("/api/v1/merchant/overview?range=7d&timezone=UTC")
                        .header("Authorization", "Bearer " + merchant))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/merchant/overview?range=7d&timezone=UTC")
                        .header("Authorization", "Bearer " + customer))
                .andExpect(status().isForbidden());
    }

    @Test
    void currentAccessTokenIsRejectedImmediatelyAfterSuspension() throws Exception {
        User merchant = user(Role.MERCHANT);
        String token = token(merchant);
        merchant.setIsActive(false);
        userRepository.saveAndFlush(merchant);

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    private User user(Role role) {
        return userRepository.saveAndFlush(User.builder()
                .fullName("Operations Security")
                .email("operations-security-" + UUID.randomUUID() + "@test.local")
                .passwordHash("not-used")
                .role(role)
                .isActive(true)
                .build());
    }

    private String token(User user) {
        return jwtService.generateToken(AuthenticatedUserPrincipal.from(user));
    }
}
