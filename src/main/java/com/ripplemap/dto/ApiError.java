package com.ripplemap.dto;

import com.ripplemap.exception.ApiException;

import java.time.Instant;
import java.util.List;

/** Error body returned by every failing API request. */
public record ApiError(
        int status,
        String error,
        String code,
        String message,
        String path,
        Instant timestamp,
        List<ApiException.FieldError> fieldErrors) {
}
