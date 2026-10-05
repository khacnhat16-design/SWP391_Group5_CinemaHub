package com.cinema.common;

import java.util.List;
import java.util.Map;

/** Envelope API thống nhất theo design.md §Error Handling. */
public record ApiResult<T>(boolean success, T data, ApiError error) {
    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(true, data, null);
    }

    public static <T> ApiResult<T> failure(ApiError error) {
        if (error == null) throw new IllegalArgumentException("error is required");
        return new ApiResult<>(false, null, error);
    }

    public record ApiError(String code, String message, Map<String, String> fieldErrors) {
        public ApiError {
            if (code == null || code.isBlank()) throw new IllegalArgumentException("code is required");
            if (message == null || message.isBlank()) throw new IllegalArgumentException("message is required");
            fieldErrors = fieldErrors == null ? Map.of() : Map.copyOf(fieldErrors);
        }

        public ApiError(String code, String message) {
            this(code, message, Map.of());
        }
    }
}
