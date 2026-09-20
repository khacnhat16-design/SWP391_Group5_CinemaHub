package com.cinema.common;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Central mapping from service/system failures to safe API responses. */
public final class ErrorMapper {
    private static final Logger LOG = Logger.getLogger(ErrorMapper.class.getName());

    private ErrorMapper() { }

    public static int httpStatus(Throwable failure) {
        return failure instanceof ServiceException service ? service.httpStatus() : 500;
    }

    public static <T> ApiResult<T> fromValidation(ValidationErrors errors) {
        if (errors == null || errors.isEmpty()) {
            throw new IllegalArgumentException("validation errors are required");
        }
        return ApiResult.failure(new ApiResult.ApiError(
                "VALIDATION_ERROR", "Dữ liệu không hợp lệ.", errors.asMap()));
    }

    public static <T> ApiResult<T> toResult(Throwable failure) {
        if (failure instanceof ServiceException service) {
            return ApiResult.failure(new ApiResult.ApiError(service.code(), service.getMessage()));
        }
        LOG.log(Level.SEVERE, "Unexpected system exception", failure);
        return ApiResult.failure(new ApiResult.ApiError(
                "INTERNAL_ERROR", "Đã xảy ra lỗi hệ thống. Vui lòng thử lại sau.", Map.of()));
    }
}
