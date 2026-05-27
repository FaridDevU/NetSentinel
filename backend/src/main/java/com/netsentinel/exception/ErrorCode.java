package com.netsentinel.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST),
    INVALID_TARGET(HttpStatus.BAD_REQUEST),
    INVALID_SCAN_PROFILE(HttpStatus.BAD_REQUEST),
    SCAN_NOT_FOUND(HttpStatus.NOT_FOUND),
    SCAN_NOT_CANCELLABLE(HttpStatus.BAD_REQUEST),
    EXPORT_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
    NETWORK_DETECTION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
    SANDBOX_UNAVAILABLE(HttpStatus.BAD_GATEWAY),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
