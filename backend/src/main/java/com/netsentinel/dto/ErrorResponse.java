package com.netsentinel.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String code,
        String error,
        String traceId,
        List<FieldError> fieldErrors
) {

    public ErrorResponse(String code, String error, String traceId) {
        this(code, error, traceId, null);
    }

    public ErrorResponse(String error) {
        this(null, error, null, null);
    }

    public record FieldError(String field, String message) {}
}
