package com.netsentinel.exception;

import com.netsentinel.config.TraceIdFilter;
import com.netsentinel.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException e) {
        if (e.getCode().status().is5xxServerError()) {
            log.error("ApiException 5xx: {}", e.getMessage(), e);
        } else {
            log.warn("ApiException {}: {}", e.getCode(), e.getMessage());
        }
        return ResponseEntity.status(e.getCode().status())
                .body(new ErrorResponse(e.getCode().name(), e.getMessage(), traceId()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        List<ErrorResponse.FieldError> fields = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ErrorResponse.FieldError(fe.getField(), defaultMessage(fe)))
                .toList();
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.status())
                .body(new ErrorResponse(ErrorCode.INVALID_REQUEST.name(),
                        "Validation failed", traceId(), fields));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.status())
                .body(new ErrorResponse(ErrorCode.INVALID_REQUEST.name(),
                        "Malformed request body", traceId()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.status())
                .body(new ErrorResponse(ErrorCode.INVALID_REQUEST.name(),
                        "Required parameter: " + e.getParameterName(), traceId()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.status())
                .body(new ErrorResponse(ErrorCode.INVALID_REQUEST.name(),
                        "Invalid value for parameter: " + e.getName(), traceId()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArg(IllegalArgumentException e) {
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.status())
                .body(new ErrorResponse(ErrorCode.INVALID_REQUEST.name(), e.getMessage(), traceId()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAny(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(ErrorCode.INTERNAL.status())
                .body(new ErrorResponse(ErrorCode.INTERNAL.name(),
                        "Internal server error", traceId()));
    }

    private static String defaultMessage(FieldError fe) {
        return fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid value";
    }

    private static String traceId() {
        return MDC.get(TraceIdFilter.MDC_KEY);
    }
}
