package com.actilazion.aries_transaction.config;

import com.actilazion.aries_transaction.identity.application.AuthenticatedUserPrincipal;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Enumeration;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,@NonNull HttpServletResponse response,@NonNull FilterChain filterChain) throws ServletException, IOException {
        if ("POST".equalsIgnoreCase(request.getMethod())
                && SecurityEndpoints.LOGOUT.equals(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        Enumeration<String> authorizationHeaders = request.getHeaders("Authorization");
        if (authorizationHeaders != null && authorizationHeaders.hasMoreElements()) {
            final String authHeader = authorizationHeaders.nextElement();
            if (authorizationHeaders.hasMoreElements()) {
                reject(response);
                return;
            }
            if (authHeader == null || !authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
                reject(response);
                return;
            }
            final String jwt = authHeader.substring(7);
            if (jwt.isBlank() || jwt.length() > 4096) {
                reject(response);
                return;
            }
            authenticate(jwt, request, response);
            if (response.isCommitted()) {
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(String jwt, HttpServletRequest request, HttpServletResponse response) throws IOException {
        final java.util.UUID userId;

        try {
            userId = jwtService.extractUserId(jwt);
        } catch (Exception e) {
            log.debug("JWT subject extraction failed: {}", e.getClass().getSimpleName());
            reject(response);
            return;
        }

        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                User user = userRepository.findById(userId).orElseThrow();
                AuthenticatedUserPrincipal principal = AuthenticatedUserPrincipal.from(user);
                if (!principal.isEnabled() || !jwtService.isTokenValid(jwt, principal)) {
                    reject(response);
                    return;
                }

                UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);
                log.trace("JWT authentication succeeded for userId={} authorities={}", userId, principal.getAuthorities());
            } catch (Exception e) {
                log.debug("JWT authentication failed: {}", e.getClass().getSimpleName());
                reject(response);
                return;
            }
        }
    }

    private void reject(HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.flushBuffer();
    }
}
