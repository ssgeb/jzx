package com.ruanzhu.doorhandlecatch.common;

public record ApiResponse<T>(boolean success, T data, ErrorDetail error, String requestId) {

    public static <T> ApiResponse<T> ok(T data, String requestId) {
        return new ApiResponse<>(true, data, null, requestId);
    }

    public static <T> ApiResponse<T> fail(ErrorCode code, String message, String requestId) {
        return new ApiResponse<>(false, null, new ErrorDetail(code.name(), message), requestId);
    }

    public record ErrorDetail(String code, String message) {
    }
}
