package com.fincore.common.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.common.exception.DependencyUnavailableException;
import com.fincore.common.exception.FincoreException;
import com.fincore.security.filter.InternalApiKeyFilter;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Thin client for service-to-service calls. It adds the internal key and timeouts, and translates
 * the callee's RFC 7807 error back into a {@link FincoreException} with the same status and code,
 * so a business rejection in another service ("INSUFFICIENT_FUNDS") looks the same to the caller as
 * one raised locally. Network failures and 5xx become {@link DependencyUnavailableException}.
 */
public class InternalRestClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient client;

    public InternalRestClient(
            String baseUrl, String apiKey, Duration connectTimeout, Duration readTimeout) {
        var factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        factory.setReadTimeout(readTimeout);
        this.client =
                RestClient.builder()
                        .baseUrl(baseUrl)
                        .requestFactory(factory)
                        .defaultHeader(InternalApiKeyFilter.HEADER, apiKey)
                        .defaultStatusHandler(
                                HttpStatusCode::isError,
                                (request, response) -> {
                                    throw translate(
                                            response.getStatusCode(),
                                            readBody(response.getBody().readAllBytes()));
                                })
                        .build();
    }

    public <T> T get(String uri, Class<T> type, Object... uriVariables) {
        return call(() -> client.get().uri(uri, uriVariables).retrieve().body(type));
    }

    public <T> T post(String uri, Object body, Class<T> type, Object... uriVariables) {
        return call(
                () ->
                        client.post()
                                .uri(uri, uriVariables)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(body)
                                .retrieve()
                                .body(type));
    }

    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (ResourceAccessException e) {
            throw new DependencyUnavailableException(
                    "Downstream service unreachable: " + e.getMessage());
        }
    }

    private static JsonNode readBody(byte[] bytes) {
        try {
            return bytes.length == 0 ? null : MAPPER.readTree(bytes);
        } catch (IOException e) {
            return null;
        }
    }

    private static FincoreException translate(HttpStatusCode status, JsonNode problem) {
        if (status.is5xxServerError()) {
            return new DependencyUnavailableException(
                    "Downstream service failed with HTTP " + status.value());
        }
        String code =
                problem != null && problem.hasNonNull("code")
                        ? problem.get("code").asText()
                        : "DOWNSTREAM_REJECTED";
        String detail =
                problem != null && problem.hasNonNull("detail")
                        ? problem.get("detail").asText()
                        : "Request rejected";
        return new FincoreException(HttpStatus.valueOf(status.value()), code, detail);
    }
}
