package com.cinema.common;

import java.util.LinkedHashMap;
import java.util.Map;

/** Lỗi đầu vào theo field, dùng cho HTTP 400 và envelope API. */
public final class ValidationErrors {
    private final Map<String, String> fields = new LinkedHashMap<>();

    public ValidationErrors add(String field, String message) {
        if (field == null || field.isBlank()) throw new IllegalArgumentException("field is required");
        if (message == null || message.isBlank()) throw new IllegalArgumentException("message is required");
        fields.putIfAbsent(field, message);
        return this;
    }

    public boolean isEmpty() { return fields.isEmpty(); }
    public Map<String, String> asMap() { return Map.copyOf(fields); }
}
