package com.fincore.common.exception;

import org.springframework.http.HttpStatus;

/** Business exception carrying an HTTP status and a stable machine-readable error code. */
public class FincoreException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public FincoreException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static FincoreException notFound(String message) {
        return new FincoreException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static FincoreException badRequest(String code, String message) {
        return new FincoreException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static FincoreException unauthorized(String message) {
        return new FincoreException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
    }

    public static FincoreException conflict(String code, String message) {
        return new FincoreException(HttpStatus.CONFLICT, code, message);
    }

    public static FincoreException unprocessable(String code, String message) {
        return new FincoreException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
