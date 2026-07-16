package com.actilazion.aries_transaction.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.function.Function;

import static io.jsonwebtoken.Jwts.*;

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

    public String generateToken(UserDetails userDetails) {
        return generateToken(new HashMap<>(), userDetails);
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return username != null && !username.isBlank() && username.equals(userDetails.getUsername());
    }

    public String generateToken(HashMap<String, Object> claims, UserDetails userDetails) {
        long nowMs = System.currentTimeMillis();
        return builder()
                .setClaims(claims)
                .setSubject(userDetails.getUsername())
                .setIssuer(jwtConfig.getIssuer())
                .setAudience(jwtConfig.getAudience())
                .setIssuedAt(new Date(nowMs))
                .setExpiration(new Date(nowMs + jwtConfig.getExpiration() * 1000))
                .claim(TOKEN_TYPE_CLAIM, jwtConfig.getTokenType())
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    private boolean isTokenExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }

    private <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = Jwts.parser()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
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
