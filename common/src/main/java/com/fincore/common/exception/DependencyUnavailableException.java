package com.fincore.common.exception;

import org.springframework.http.HttpStatus;

/**
 * A downstream service could not be reached or failed with a 5xx. Distinct from a business rejection so that
 * retries and circuit breakers act only on this type, never on e.g. "insufficient funds".
 */
public class DependencyUnavailableException extends FincoreException {

    public DependencyUnavailableException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", message);
    }
}
