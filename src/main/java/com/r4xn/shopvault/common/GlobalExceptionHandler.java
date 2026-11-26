package com.r4xn.shopvault.common;

import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every failure into an RFC 7807 problem response. Unexpected exceptions are logged with
 * the request id and answered with a generic message, so internals never reach the client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
        return problem(ex.status(), ex.code(), ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "forbidden", "You do not have permission to perform this action");
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "unauthorized", "Authentication is required");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
        List<Map<String, String>> errors = ex.getConstraintViolations().stream()
                .map(v -> Map.of("field", v.getPropertyPath().toString(), "message", v.getMessage()))
                .toList();
        ResponseEntity<ProblemDetail> response =
                problem(HttpStatus.BAD_REQUEST, "validation_failed", "The request is invalid");
        response.getBody().setProperty("errors", errors);
        return response;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException ex) {
        log.info("Data integrity violation: {}", ex.getMostSpecificCause().getClass().getSimpleName());
        return problem(HttpStatus.CONFLICT, "conflict", "The request conflicts with existing data");
    }

    @ExceptionHandler({OptimisticLockingFailureException.class, PessimisticLockingFailureException.class})
    ResponseEntity<ProblemDetail> handleConcurrency(RuntimeException ex) {
        return problem(HttpStatus.CONFLICT, "concurrent_update",
                "The resource was changed by another request, please retry");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "An unexpected error occurred");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        ex.getBindingResult().getFieldErrors().forEach(e -> errors.add(fieldError(e.getField(), e.getDefaultMessage())));
        return validationProblem(errors);
    }

    /** Raised instead of the above when a handler also validates headers or query parameters. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors parameterErrors) {
                parameterErrors.getFieldErrors()
                        .forEach(e -> errors.add(fieldError(e.getField(), e.getDefaultMessage())));
            } else {
                String name = parameterName(result.getMethodParameter());
                result.getResolvableErrors().forEach(e -> errors.add(fieldError(name, e.getDefaultMessage())));
            }
        }
        return validationProblem(errors);
    }

    private static String parameterName(MethodParameter parameter) {
        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        if (header != null && !header.name().isEmpty()) {
            return header.name();
        }
        String name = parameter.getParameterName();
        return name == null ? "parameter" : name;
    }

    private static Map<String, String> fieldError(String field, String message) {
        return Map.of("field", field, "message", message == null ? "is invalid" : message);
    }

    private static ResponseEntity<Object> validationProblem(List<Map<String, String>> errors) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request is invalid");
        body.setProperty("code", "validation_failed");
        body.setProperty("errors", errors);
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail detail
                && detail.getProperties() == null) {
            detail.setProperty("code", codeFor(statusCode));
        }
        return response;
    }

    private static String codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "bad_request";
            case 404 -> "not_found";
            case 405 -> "method_not_allowed";
            case 406 -> "not_acceptable";
            case 413 -> "payload_too_large";
            case 415 -> "unsupported_media_type";
            default -> "error";
        };
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setProperty("code", code);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }
}
