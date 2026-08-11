package com.actilazion.aries_transaction.security;

import com.actilazion.aries_transaction.config.AuthRateLimitConfig;
import com.actilazion.aries_transaction.config.ClientIpResolver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {
    @Test
    void ignoresForwardedHeaderFromUntrustedRemote() {
        AuthRateLimitConfig config = new AuthRateLimitConfig();
        config.setTrustedProxies(List.of("10.0.0.10"));
        ClientIpResolver resolver = new ClientIpResolver(config);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("X-Forwarded-For", "198.51.100.10");

        assertThat(resolver.resolve(request)).isEqualTo("192.0.2.10");
    }

    @Test
    void resolvesClientFromForwardedChainWhenProxyIsTrusted() {
        AuthRateLimitConfig config = new AuthRateLimitConfig();
        config.setTrustedProxies(List.of("10.0.0.10", "10.0.0.11"));
        ClientIpResolver resolver = new ClientIpResolver(config);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.10");
        request.addHeader("X-Forwarded-For", "198.51.100.10, 10.0.0.11");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.10");
    }
}
