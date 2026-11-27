package com.fincore.security.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

public class InternalApiKeyFilterTest {

    private final InternalApiKeyFilter filter = new InternalApiKeyFilter("secret-key");
    private final FilterChain chain = mock(FilterChain.class);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private boolean authenticatedAfter(MockHttpServletRequest request) throws Exception {
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return SecurityContextHolder.getContext().getAuthentication() != null;
    }

    @Test
    void correctKeyOnInternalPathGrantsInternalRole() throws Exception {
        var request = new MockHttpServletRequest("POST", "/internal/ledger/payment-debits");
        request.addHeader(InternalApiKeyFilter.HEADER, "secret-key");

        assertThat(authenticatedAfter(request)).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .anyMatch(a -> a.getAuthority().equals("ROLE_INTERNAL"));
        verify(chain).doFilter(eq(request), any());
    }

    @Test
    void wrongOrMissingKeyDoesNotAuthenticate() throws Exception {
        var wrong = new MockHttpServletRequest("GET", "/internal/accounts/1");
        wrong.addHeader(InternalApiKeyFilter.HEADER, "nope");
        assertThat(authenticatedAfter(wrong)).isFalse();

        assertThat(authenticatedAfter(new MockHttpServletRequest("GET", "/internal/accounts/1")))
                .isFalse();
    }

    @Test
    void keyIsIgnoredOutsideInternalPaths() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/accounts");
        request.addHeader(InternalApiKeyFilter.HEADER, "secret-key");

        assertThat(authenticatedAfter(request)).isFalse();
    }
}
