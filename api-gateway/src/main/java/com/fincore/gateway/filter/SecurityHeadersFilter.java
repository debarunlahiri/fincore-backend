package com.fincore.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Adds browser-hardening headers to every response, including the ones the gateway writes itself
 * (429, 400...). This is a JSON API, so the content security policy forbids everything: if an API
 * response is ever rendered as a page, no script can run in it. Responses are never cached, because
 * they hold account data.
 */
@Component
public class SecurityHeadersFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        exchange.getResponse()
                .beforeCommit(
                        () -> {
                            HttpHeaders h = exchange.getResponse().getHeaders();
                            h.set("X-Content-Type-Options", "nosniff");
                            h.set("X-Frame-Options", "DENY");
                            h.set("Referrer-Policy", "no-referrer");
                            h.set(
                                    "Content-Security-Policy",
                                    "default-src 'none'; frame-ancestors 'none'");
                            h.set(
                                    "Strict-Transport-Security",
                                    "max-age=31536000; includeSubDomains");
                            h.set(
                                    "Permissions-Policy",
                                    "geolocation=(), camera=(), microphone=(), payment=()");
                            h.set("Cross-Origin-Resource-Policy", "same-site");
                            h.setCacheControl("no-store");
                            h.set("Pragma", "no-cache");
                            h.remove("Server");
                            h.remove("X-Powered-By");
                            return Mono.empty();
                        });
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
