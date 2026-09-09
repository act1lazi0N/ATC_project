package com.actilazion.aries_transaction.webhook.domain;

import com.actilazion.aries_transaction.identity.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.FetchType;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "webhook_endpoints",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_webhook_endpoints_owner_url",
                columnNames = {"owner_id", "canonical_url"}
        )
)
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookEndpoint {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "canonical_url", nullable = false, length = 2048)
    private String canonicalUrl;

    @Column(name = "signing_secret_ciphertext", nullable = false, length = 1024)
    private String signingSecretCiphertext;

    @Column(name = "signing_secret_nonce", nullable = false, length = 128)
    private String signingSecretNonce;

    @Column(name = "secret_key_version", nullable = false, length = 50)
    private String secretKeyVersion;

    @Column(name = "secret_hint", nullable = false, length = 32)
    private String secretHint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private WebhookEndpointState state = WebhookEndpointState.ENABLED;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private long version = 0L;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
