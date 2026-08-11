package com.actilazion.aries_transaction.identity.application;

import com.actilazion.aries_transaction.identity.domain.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Getter
public final class AuthenticatedUserPrincipal implements UserDetails {
    private final UUID userId;
    private final String email;
    private final String passwordHash;
    private final Collection<? extends GrantedAuthority> authorities;
    private final boolean active;

    private AuthenticatedUserPrincipal(User user) {
        this.userId = user.getId();
        this.email = user.getEmail();
        this.passwordHash = user.getPasswordHash();
        this.authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
        this.active = Boolean.TRUE.equals(user.getIsActive())
                && (user.getLockedUntil() == null || user.getLockedUntil().isBefore(java.time.OffsetDateTime.now()));
    }

    public static AuthenticatedUserPrincipal from(User user) {
        if (user.getId() == null) {
            throw new IllegalArgumentException("Authenticated user must have an id");
        }
        return new AuthenticatedUserPrincipal(user);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
    @Override
    public String getPassword() { return passwordHash; }
    @Override
    public String getUsername() { return email; }
    @Override
    public boolean isAccountNonExpired() { return true; }
    @Override
    public boolean isAccountNonLocked() { return active; }
    @Override
    public boolean isCredentialsNonExpired() { return true; }
    @Override
    public boolean isEnabled() { return active; }
}
