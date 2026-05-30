package com.example.orchestrator.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ApiResult<T> {
    private int code;
    private String message;
    private T data;

    public static <T> ApiResult<T> ok(T data) { return new ApiResult<>(0, "success", data); }
    public static <T> ApiResult<T> ok() { return ok(null); }
    public static <T> ApiResult<T> error(String msg) { return new ApiResult<>(1, msg, null); }
    public static <T> ApiResult<T> error(int code, String msg) { return new ApiResult<>(code, msg, null); }
}
