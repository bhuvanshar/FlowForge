package com.flowforge.api.error;

import com.flowforge.application.ConflictException;
import com.flowforge.application.FieldValidationException;
import com.flowforge.application.credential.CredentialInUseException;
import com.flowforge.application.credential.CredentialsUnavailableException;
import com.flowforge.application.InvalidRequestException;
import com.flowforge.application.PayloadTooLargeException;
import com.flowforge.application.ResourceNotFoundException;
import com.flowforge.application.WorkflowValidationException;
import com.flowforge.application.execution.ExecutionCapacityException;
import com.flowforge.application.execution.ExecutionNotSupportedException;
import com.flowforge.application.trigger.WebhookRejectedException;
import com.flowforge.api.dto.ValidationResultResponse;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every error to an RFC 9457 Problem Details body ({@code application/problem+json}).
 * Validation failures carry an {@code errors} extension listing {@code field} and {@code message}.
 * Unexpected exceptions are logged and returned without internal details.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TYPE_BASE = "https://flowforge.local/problems/";

    public record FieldViolation(String field, String message) {
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<FieldViolation> errors = ex.getBindingResult().getFieldErrors().stream()
                .map((FieldError e) -> new FieldViolation(e.getField(), e.getDefaultMessage()))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                "Request validation failed.");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        List<FieldViolation> errors = ex.getConstraintViolations().stream()
                .map(v -> new FieldViolation(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                "Request validation failed.");
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "not-found", "Resource not found", ex.getMessage());
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ProblemDetail handleInvalidRequest(InvalidRequestException ex) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException ex) {
        return problem(HttpStatus.CONFLICT, "conflict", "Conflict", ex.getMessage());
    }

    @ExceptionHandler(FieldValidationException.class)
    public ProblemDetail handleFieldValidation(FieldValidationException ex) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                "Request validation failed.");
        problem.setProperty("errors", ex.violations().stream()
                .map(v -> new FieldViolation(v.field(), v.message())).toList());
        return problem;
    }

    @ExceptionHandler(CredentialInUseException.class)
    public ProblemDetail handleCredentialInUse(CredentialInUseException ex) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "credential-in-use", "Credential in use", ex.getMessage());
        problem.setProperty("workflows", ex.workflows());
        return problem;
    }

    /** A stored secret cannot be decrypted (key missing or data altered). No key material or ciphertext details leak. */
    @ExceptionHandler(com.flowforge.application.credential.CredentialCryptoException.class)
    public ProblemDetail handleCrypto(com.flowforge.application.credential.CredentialCryptoException ex) {
        log.error("Secret decryption failed: {}", ex.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "secret-unavailable", "Secret unavailable",
                "The stored secret cannot be decrypted on this server.");
    }

    @ExceptionHandler(CredentialsUnavailableException.class)
    public ProblemDetail handleCredentialsUnavailable(CredentialsUnavailableException ex) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "credentials-unavailable", "Credentials unavailable",
                ex.getMessage());
    }

    /** A constraint (e.g. a unique name) rejected a write that raced with another one. No database details leak. */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(org.springframework.dao.DataIntegrityViolationException ex) {
        log.warn("Constraint violation: {}", ex.getMostSpecificCause().getClass().getSimpleName());
        return problem(HttpStatus.CONFLICT, "conflict", "Conflict",
                "The change conflicts with existing data. Reload and retry.");
    }

    /**
     * Malformed or unknown JSON. The body is never echoed (it may contain secrets) and parser messages are not passed on.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            org.springframework.http.converter.HttpMessageNotReadableException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        for (Throwable c = ex; c != null; c = c.getCause()) {
            if (c instanceof com.flowforge.api.security.RequestGuardFilter.RequestTooLargeException) {
                return handleExceptionInternal(ex, problem(HttpStatus.PAYLOAD_TOO_LARGE, "payload-too-large",
                        "Payload too large", "The request body is too large."), headers, HttpStatus.PAYLOAD_TOO_LARGE,
                        request);
            }
        }
        String detail = "The request body is not valid JSON for this endpoint.";
        if (ex.getCause() instanceof com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException unknown) {
            detail = "Unknown property '" + unknown.getPropertyName() + "'.";
        } else {
            for (Throwable c = ex.getCause(); c != null; c = c.getCause()) {
                if (c instanceof com.fasterxml.jackson.core.exc.StreamConstraintsException) {
                    detail = "The request body exceeds the allowed nesting depth or value sizes.";
                    break;
                }
            }
        }
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", detail);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request",
                "Parameter '" + ex.getName() + "' has an invalid value.");
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    public ProblemDetail handlePayloadTooLarge(PayloadTooLargeException ex) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "payload-too-large", "Payload too large", ex.getMessage());
    }

    @ExceptionHandler(WorkflowValidationException.class)
    public ProblemDetail handleWorkflowInvalid(WorkflowValidationException ex) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, "workflow-invalid", "Workflow is invalid",
                ex.getMessage());
        problem.setProperty("issues", ValidationResultResponse.from(ex.result()).issues());
        return problem;
    }

    @ExceptionHandler(ExecutionNotSupportedException.class)
    public ProblemDetail handleNotExecutable(ExecutionNotSupportedException ex) {
        ProblemDetail problem = problem(HttpStatus.NOT_IMPLEMENTED, "node-type-not-executable",
                "Node type not executable", ex.getMessage());
        problem.setProperty("nodeTypes", ex.nodeTypes());
        return problem;
    }

    @ExceptionHandler(ExecutionCapacityException.class)
    public ProblemDetail handleCapacity(ExecutionCapacityException ex) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "execution-capacity", "Execution capacity reached",
                ex.getMessage());
    }

    @ExceptionHandler(WebhookRejectedException.class)
    public ResponseEntity<ProblemDetail> handleWebhookRejected(WebhookRejectedException ex) {
        ProblemDetail problem = problem(HttpStatus.valueOf(ex.status()), ex.code(), ex.title(), ex.getMessage());
        HttpHeaders headers = new HttpHeaders();
        ex.headers().forEach(headers::set);
        return ResponseEntity.status(ex.status()).headers(headers)
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    /** A concurrent write won the race after our own version check passed. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, "concurrent-modification", "Concurrent modification",
                "The resource was modified concurrently. Reload it and retry.");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal server error",
                "An unexpected error occurred.");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + code));
        problem.setTitle(title);
        return problem;
    }
}
