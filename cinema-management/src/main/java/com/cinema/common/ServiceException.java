package com.cinema.common;

/** Exception nghiệp vụ có mã lỗi và HTTP status chuẩn hóa. */
public class ServiceException extends RuntimeException {
    private final String code;
    private final int httpStatus;

    public ServiceException(String code, String message, int httpStatus) {
        super(message);
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code is required");
        if (message == null || message.isBlank()) throw new IllegalArgumentException("message is required");
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() { return code; }
    public int httpStatus() { return httpStatus; }

    public static final class Validation extends ServiceException {
        public Validation(String message) { super("VALIDATION_ERROR", message, 400); }
    }
    public static final class Unauthorized extends ServiceException {
        public Unauthorized(String message) { super("UNAUTHORIZED", message, 401); }
    }
    public static final class Forbidden extends ServiceException {
        public Forbidden(String message) { super("FORBIDDEN", message, 403); }
    }
    public static final class NotFound extends ServiceException {
        public NotFound(String message) { super("NOT_FOUND", message, 404); }
    }
    public static final class Conflict extends ServiceException {
        public Conflict(String message) { super("CONFLICT", message, 409); }
    }
    public static final class BusinessRule extends ServiceException {
        public BusinessRule(String code, String message) { super(code, message, 422); }
    }
}
