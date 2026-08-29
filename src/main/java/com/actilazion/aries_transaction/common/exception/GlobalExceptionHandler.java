package com.actilazion.aries_transaction.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    private static final String NO_STORE = "no-store";

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ErrorResponse> handleAppException(AppException ex) {
        return noStore(ex.getHttpStatus())
                .body(new ErrorResponse(
                        ex.getHttpStatus().value(),
                        ex.getMessage(),
                        null,
                        errorCode(ex),
                        requestId()
                ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex) {

        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(error.getField(), error.getDefaultMessage());
        }

        return noStore(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(
                        HttpStatus.BAD_REQUEST.value(),
                        "Validation failed",
                        fieldErrors,
                        "VALIDATION_ERROR",
                        requestId()
                ));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimit(RateLimitExceededException ex) {
        return noStore(ex.getHttpStatus())
                .header("Retry-After", Long.toString(ex.getRetryAfterSeconds()))
                .body(new ErrorResponse(
                        ex.getHttpStatus().value(),
                        ex.getMessage(),
                        null,
                        "RATE_LIMITED",
                        requestId()
                ));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex) {
        return noStore(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse(
                        HttpStatus.UNAUTHORIZED.value(),
                        "Unauthorized",
                        null,
                        "UNAUTHORIZED",
                        requestId()
                ));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return noStore(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse(
                        HttpStatus.FORBIDDEN.value(),
                        "Access denied",
                        null,
                        "FORBIDDEN",
                        requestId()
                ));
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex) {
        return noStore(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(
                        HttpStatus.BAD_REQUEST.value(),
                        badRequestMessage(ex),
                        null,
                        "BAD_REQUEST",
                        requestId()
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unexpected error: ", ex);
        return noStore(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "An unexpected error occurred.",
                        null,
                        "INTERNAL_ERROR",
                        requestId()
                ));
    }

    private String badRequestMessage(Exception ex) {
        if (ex instanceof MethodArgumentTypeMismatchException mismatch) {
            return "Invalid request parameter: " + mismatch.getName();
        }
        if (ex instanceof HttpMessageNotReadableException) {
            return "Malformed JSON request";
        }
        return ex.getMessage() != null ? ex.getMessage() : "Bad request";
    }

    private ResponseEntity.BodyBuilder noStore(HttpStatus status) {
        return ResponseEntity.status(status).header("Cache-Control", NO_STORE);
    }

    private String errorCode(AppException ex) {
        return ex.getClass().getSimpleName()
                .replaceAll("Exception$", "")
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .toUpperCase(java.util.Locale.ROOT);
    }

    private String requestId() {
        return java.util.UUID.randomUUID().toString();
    }

    public record ErrorResponse(
            int status,
            String message,
            Map<String, String> errors,
            OffsetDateTime timestamp,
            String code,
            String requestId
    ) {
        // Convenience constructor - injects the current timestamp.
        public ErrorResponse(int status, String message, Map<String, String> errors) {
            this(status, message, errors, OffsetDateTime.now(), "ERROR", java.util.UUID.randomUUID().toString());
        }

        public ErrorResponse(int status, String message, Map<String, String> errors, String code, String requestId) {
            this(status, message, errors, OffsetDateTime.now(), code, requestId);
        }
    }
}
