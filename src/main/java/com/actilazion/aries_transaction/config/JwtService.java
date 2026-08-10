package com.actilazion.aries_transaction.config;

import com.actilazion.aries_transaction.identity.application.AuthenticatedUserPrincipal;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.function.Function;
import java.util.UUID;
import javax.crypto.SecretKey;

import static io.jsonwebtoken.Jwts.builder;
import static io.jsonwebtoken.Jwts.parser;

@Service
@RequiredArgsConstructor
public class JwtService {
    private static final String TOKEN_TYPE_CLAIM = "typ";

    private final JwtConfig jwtConfig;

    @PostConstruct
    void validateConfiguration() {
        getSigningKey();
        requireText(jwtConfig.getIssuer(), "jwt.issuer");
        requireText(jwtConfig.getAudience(), "jwt.audience");
        requireText(jwtConfig.getTokenType(), "jwt.token-type");
    }

    public String generateToken(AuthenticatedUserPrincipal principal) {
        return generateToken(new HashMap<>(), principal.getUserId().toString());
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public UUID extractUserId(String token) {
        try {
            return UUID.fromString(extractUsername(token));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("JWT subject must be a user UUID", ex);
        }
    }

    public boolean isTokenValid(String token, AuthenticatedUserPrincipal principal) {
        return principal.getUserId().equals(extractUserId(token));
    }

    private String generateToken(HashMap<String, Object> claims, String subject) {
        long nowMs = System.currentTimeMillis();
        return builder()
                .claims(claims)
                .subject(subject)
                .issuer(jwtConfig.getIssuer())
                .audience()
                .add(jwtConfig.getAudience())
                .and()
                .claim(TOKEN_TYPE_CLAIM, jwtConfig.getTokenType())
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date(nowMs))
                .expiration(new Date(nowMs + jwtConfig.getExpiration() * 1000))
                .signWith(getSigningKey(), Jwts.SIG.HS256)
                .compact();
    }

    private <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = parser()
                .verifyWith(getSigningKey())
                .clockSkewSeconds(30)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        validateClaims(claims);
        return claimsResolver.apply(claims);
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(jwtConfig.getSecret());
        return Keys.hmacShaKeyFor(keyBytes);
    }

    private void requireText(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " must not be blank");
        }
    }

    private void validateClaims(Claims claims) {
        if (!jwtConfig.getIssuer().equals(claims.getIssuer())) {
            throw new IllegalArgumentException("JWT issuer is invalid");
        }
        if (!audienceMatches(claims.get(Claims.AUDIENCE))) {
            throw new IllegalArgumentException("JWT audience is invalid");
        }
        if (!jwtConfig.getTokenType().equals(claims.get(TOKEN_TYPE_CLAIM, String.class))) {
            throw new IllegalArgumentException("JWT token type is invalid");
        }
        if (claims.getIssuedAt() == null || claims.getExpiration() == null
                || claims.getId() == null || claims.getId().isBlank()) {
            throw new IllegalArgumentException("JWT temporal claims are required");
        }
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("JWT subject is required");
        }
    }

    private boolean audienceMatches(Object audience) {
        if (audience instanceof String value) {
            return jwtConfig.getAudience().equals(value);
        }
        if (audience instanceof Collection<?> values) {
            return values.contains(jwtConfig.getAudience());
        }
        return false;
    }
}
