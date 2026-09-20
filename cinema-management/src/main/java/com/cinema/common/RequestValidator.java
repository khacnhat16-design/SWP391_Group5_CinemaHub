package com.cinema.common;

import java.util.regex.Pattern;

/** Builder validation field-level, không chứa logic nghiệp vụ domain. */
public final class RequestValidator {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private final ValidationErrors errors = new ValidationErrors();

    private RequestValidator() { }

    public static RequestValidator builder() { return new RequestValidator(); }

    public RequestValidator required(String field, String value) {
        if (value == null || value.isBlank()) {
            errors.add(field, field + " không được để trống");
        }
        return this;
    }

    public RequestValidator email(String field, String value) {
        if (value != null && !value.isBlank() && !EMAIL.matcher(value).matches()) {
            errors.add(field, field + " không đúng định dạng email");
        }
        return this;
    }

    public RequestValidator minLength(String field, String value, int minimum) {
        if (value != null && value.length() < minimum) {
            errors.add(field, field + " phải có ít nhất " + minimum + " ký tự");
        }
        return this;
    }

    public ValidationErrors build() { return errors; }
}
