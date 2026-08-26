package com.actilazion.aries_transaction.config;

import com.actilazion.aries_transaction.common.exception.CsrfOriginException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import lombok.Setter;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "security.refresh-cookie")
public class RefreshCookiePolicy {
    @NotBlank
    private String allowedOrigins = "";

    public List<String> allowedOriginList() {
        return Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    public void enforce(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        Set<String> allowed = Set.copyOf(allowedOriginList());
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
