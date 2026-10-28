package com.ripplemap.exception;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * An error with a stable machine-readable code and a message that is safe to show to users.
 * Messages must never contain stack traces, absolute server paths or secrets.
 */
public class ApiException extends RuntimeException {

    public record FieldError(String field, String message) {
    }

    private final HttpStatus status;
    private final String code;
    private final List<FieldError> fieldErrors;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public ApiException(HttpStatus status, String code, String message, List<FieldError> fieldErrors) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException validation(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message,
                List.of(new FieldError(field, message)));
    }

    public static ApiException validation(List<FieldError> errors) {
        String message = errors.isEmpty() ? "The request is invalid" : errors.get(0).message();
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, errors);
    }
}
