package com.github.guoyyit.imagelayerunpackerjava.error;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static class NotFound extends ApiException {
        public NotFound(String message) {
            super(HttpStatus.NOT_FOUND, message);
        }
    }

    public static class Conflict extends ApiException {
        public Conflict(String message) {
            super(HttpStatus.CONFLICT, message);
        }
    }

    public static class QuotaExceeded extends ApiException {
        public QuotaExceeded(String message) {
            super(HttpStatus.PAYLOAD_TOO_LARGE, message);
        }
    }

    public static class UnsafeArchive extends ApiException {
        public UnsafeArchive(String message) {
            super(HttpStatus.BAD_REQUEST, message);
        }
    }

    public static class CorruptArchive extends ApiException {
        public CorruptArchive(String message) {
            super(HttpStatus.UNPROCESSABLE_ENTITY, message);
        }
    }

    public static class BadRequest extends ApiException {
        public BadRequest(String message) {
            super(HttpStatus.BAD_REQUEST, message);
        }
    }
}
