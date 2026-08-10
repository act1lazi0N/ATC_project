package com.actilazion.aries_transaction.config;

import com.actilazion.aries_transaction.common.exception.CsrfOriginException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "security.refresh-cookie")
public class RefreshCookiePolicy {
    private String allowedOrigins = "";

    public void enforce(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        Set<String> allowed = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        if (origin == null || !allowed.contains(origin)) {
            throw new CsrfOriginException();
        }

        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if (fetchSite != null && !fetchSite.equalsIgnoreCase("same-origin")
                && !fetchSite.equalsIgnoreCase("same-site")) {
            throw new CsrfOriginException();
        }
    }
}
