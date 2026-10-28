package com.ripplemap.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * Converts exceptions into error responses ({@link com.ripplemap.dto.ApiError} JSON for API requests, the HTML
 * error page otherwise). Unexpected errors are logged, never exposed.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ErrorResponder responder;

    public GlobalExceptionHandler(ErrorResponder responder) {
        this.responder = responder;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<?> handleApi(ApiException e, HttpServletRequest request,
                                    HttpServletResponse response) {
        return build(e.status(), e.code(), e.getMessage(), e.fieldErrors(), request, response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidation(MethodArgumentNotValidException e, HttpServletRequest request,
                                    HttpServletResponse response) {
        List<ApiException.FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiException.FieldError(f.getField(),
                        f.getDefaultMessage() == null ? "Invalid value" : f.getDefaultMessage()))
                .toList();
        String message = errors.isEmpty() ? "The request is invalid" : errors.get(0).message();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, errors, request, response);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<?> handleConstraint(ConstraintViolationException e, HttpServletRequest request,
                                    HttpServletResponse response) {
        List<ApiException.FieldError> errors = e.getConstraintViolations().stream()
                .map(v -> new ApiException.FieldError(lastNode(v.getPropertyPath().toString()), v.getMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                errors.isEmpty() ? "The request is invalid" : errors.get(0).message(), errors, request, response);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    public ResponseEntity<?> handleMissing(Exception e, HttpServletRequest request,
                                    HttpServletResponse response) {
        String name = e instanceof MissingServletRequestParameterException p ? p.getParameterName()
                : ((MissingServletRequestPartException) e).getRequestPartName();
        return build(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER", "Required parameter '" + name + "' is missing",
                List.of(new ApiException.FieldError(name, "Required")), request, response);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> handleTypeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request,
                                    HttpServletResponse response) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "Parameter '" + e.getName() + "' has an invalid value",
                List.of(new ApiException.FieldError(e.getName(), "Invalid value")), request, response);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleUnreadable(HttpMessageNotReadableException e, HttpServletRequest request,
                                    HttpServletResponse response) {
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "The request body is missing or malformed",
                List.of(), request, response);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> handleTooLarge(MaxUploadSizeExceededException e, HttpServletRequest request,
                                    HttpServletResponse response) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "UPLOAD_TOO_LARGE",
                "The uploaded file exceeds the maximum upload size", List.of(), request, response);
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<?> handleMultipart(MultipartException e, HttpServletRequest request,
                                    HttpServletResponse response) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_UPLOAD", "The upload could not be processed", List.of(), request, response);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<?> handleMethod(HttpRequestMethodNotSupportedException e, HttpServletRequest request,
                                    HttpServletResponse response) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "Method " + e.getMethod() + " is not supported for this resource", List.of(), request, response);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<?> handleMediaType(HttpMediaTypeNotSupportedException e, HttpServletRequest request,
                                    HttpServletResponse response) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Unsupported content type",
                List.of(), request, response);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<?> handleNoResource(NoResourceFoundException e, HttpServletRequest request,
                                    HttpServletResponse response) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "The requested resource does not exist", List.of(), request, response);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<?> handleOptimistic(OptimisticLockingFailureException e, HttpServletRequest request,
                                    HttpServletResponse response) {
        return build(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "The resource was modified concurrently; reload and try again", List.of(), request, response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleUnexpected(Exception e, HttpServletRequest request,
                                    HttpServletResponse response) {
        log.error("Unexpected error handling {} {}", request.getMethod(), request.getRequestURI(), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred. The details were written to the server log.", List.of(), request, response);
    }

    private ResponseEntity<?> build(HttpStatus status, String code, String message,
                                    List<ApiException.FieldError> fieldErrors, HttpServletRequest request,
                                    HttpServletResponse response) {
        return responder.respond(status, code, message, fieldErrors, request, response);
    }

    private static String lastNode(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }
}
