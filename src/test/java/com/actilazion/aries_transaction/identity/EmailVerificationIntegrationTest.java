package com.actilazion.aries_transaction.identity;

import com.actilazion.aries_transaction.identity.application.EmailVerificationService;
import com.actilazion.aries_transaction.identity.application.EmailVerificationTokenService;
import com.actilazion.aries_transaction.identity.domain.EmailVerificationChallenge;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.EmailVerificationChallengeRepository;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.notification.domain.EmailDeliveryPurpose;
import com.actilazion.aries_transaction.notification.infrastructure.EmailDeliveryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class EmailVerificationIntegrationTest {
    @Autowired UserRepository userRepository;
    @Autowired EmailVerificationChallengeRepository challengeRepository;
    @Autowired EmailDeliveryRepository deliveryRepository;
    @Autowired EmailVerificationService service;
    @Autowired EmailVerificationTokenService tokenService;

    @Test
    void requestQueuesOneTimeEmailAndConfirmVerifiesUser() {
        User user = user();

        assertThat(service.request(user.getId(), "127.0.0.1").emailVerified()).isFalse();
        EmailVerificationChallenge challenge = challengeRepository
                .findAllByUser_IdOrderByCreatedAtDesc(user.getId()).getFirst();
        String token = tokenService.tokenFor(challenge);

        assertThat(deliveryRepository.findAll()).anySatisfy(delivery -> {
            assertThat(delivery.getPurpose()).isEqualTo(EmailDeliveryPurpose.EMAIL_VERIFICATION);
            assertThat(delivery.getVerificationChallenge().getId()).isEqualTo(challenge.getId());
        });
        assertThat(service.confirm(token, "127.0.0.1").emailVerified()).isTrue();
        assertThat(userRepository.findById(user.getId()).orElseThrow().getEmailVerifiedAt()).isNotNull();
        assertThatThrownBy(() -> service.confirm(token, "127.0.0.1"))
                .hasMessage("Email verification token is invalid or expired");
    }

    @Test
    void resendInvalidatesEarlierChallengeAndTamperingIsRejected() {
        User user = user();
        service.request(user.getId(), null);
        EmailVerificationChallenge first = challengeRepository
                .findAllByUser_IdOrderByCreatedAtDesc(user.getId()).getFirst();
        String firstToken = tokenService.tokenFor(first);

        service.request(user.getId(), null);

        assertThatThrownBy(() -> service.confirm(firstToken, null))
                .hasMessage("Email verification token is invalid or expired");
        EmailVerificationChallenge current = challengeRepository
                .findAllByUser_IdOrderByCreatedAtDesc(user.getId()).getFirst();
        String token = tokenService.tokenFor(current);
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");
        assertThatThrownBy(() -> service.confirm(tampered, null))
                .hasMessage("Email verification token is invalid or expired");
    }

    private User user() {
        String id = UUID.randomUUID().toString();
        return userRepository.saveAndFlush(User.builder()
                .fullName("Verification " + id)
                .email("verification-" + id + "@test.local")
                .passwordHash("hashed")
                .role(Role.USER)
                .isActive(true)
                .build());
    }
}
