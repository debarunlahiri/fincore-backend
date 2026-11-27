package com.fincore.gateway.filter;

import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Cheap sanity checks that run before anything expensive: odd methods, path-traversal tricks,
 * oversized requests and bodies that are not JSON never reach the services. They also bound how
 * much work one request can cost.
 */
@Component
public class RequestGuardFilter implements GlobalFilter, Ordered {

    private static final Set<HttpMethod> ALLOWED_METHODS =
            Set.of(
                    HttpMethod.GET,
                    HttpMethod.POST,
                    HttpMethod.PUT,
                    HttpMethod.DELETE,
                    HttpMethod.OPTIONS,
                    HttpMethod.HEAD);
    private static final Set<String> SUSPICIOUS = Set.of("..", "%2e", "%00", "%5c", "\\", ";");
    private static final int MAX_PATH = 2048;
    private static final int MAX_QUERY = 4096;
    private static final int MAX_HEADERS = 64;

    private final long maxBodyBytes;

    public RequestGuardFilter(
            @Value("${fincore.security.max-body-bytes:1048576}") long maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        HttpMethod method = request.getMethod();

        if (!ALLOWED_METHODS.contains(method)) {
            return ProblemResponses.reject(
                    exchange,
                    HttpStatus.METHOD_NOT_ALLOWED,
                    "METHOD_NOT_ALLOWED",
                    "This method is not supported");
        }
        String rawPath = request.getURI().getRawPath() == null ? "" : request.getURI().getRawPath();
        String lower = rawPath.toLowerCase(Locale.ROOT);
        if (rawPath.length() > MAX_PATH || SUSPICIOUS.stream().anyMatch(lower::contains)) {
            return ProblemResponses.reject(
                    exchange,
                    HttpStatus.BAD_REQUEST,
                    "BAD_REQUEST",
                    "The request path is not valid");
        }
        String query = request.getURI().getRawQuery();
        if (query != null && query.length() > MAX_QUERY) {
            return ProblemResponses.reject(
                    exchange,
                    HttpStatus.URI_TOO_LONG,
                    "URI_TOO_LONG",
                    "The query string is too long");
        }
        HttpHeaders headers = request.getHeaders();
        if (headers.size() > MAX_HEADERS) {
            return ProblemResponses.reject(
                    exchange,
                    HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE,
                    "HEADERS_TOO_LARGE",
                    "Too many request headers");
        }
        if (headers.getContentLength() > maxBodyBytes) {
            return ProblemResponses.reject(
                    exchange,
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "PAYLOAD_TOO_LARGE",
                    "The request body is too large");
        }
        boolean hasBody =
                headers.getContentLength() > 0
                        || headers.containsKey(HttpHeaders.TRANSFER_ENCODING);
        if (hasBody
                && (HttpMethod.POST.equals(method) || HttpMethod.PUT.equals(method))
                && !isJson(headers.getContentType())) {
            return ProblemResponses.reject(
                    exchange,
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "UNSUPPORTED_MEDIA_TYPE",
                    "Send the body as application/json");
        }
        return chain.filter(exchange);
    }

    private static boolean isJson(MediaType type) {
        return type != null
                && (MediaType.APPLICATION_JSON.isCompatibleWith(type)
                        || type.getSubtype().endsWith("+json"));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }
}
