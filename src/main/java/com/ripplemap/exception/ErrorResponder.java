package com.ripplemap.exception;

import com.ripplemap.dto.ApiError;
import com.ripplemap.security.CsrfTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** Builds error responses: JSON for API requests, the styled HTML error page for browser pages. */
@Component
public class ErrorResponder {

    private final TemplateEngine templates;
    private final CsrfTokenService csrf;

    public ErrorResponder(TemplateEngine templates, CsrfTokenService csrf) {
        this.templates = templates;
        this.csrf = csrf;
    }

    public ResponseEntity<?> respond(HttpStatus status, String code, String message,
                                     List<ApiException.FieldError> fieldErrors, HttpServletRequest request,
                                     HttpServletResponse response) {
        String path = originalPath(request);
        if (wantsJson(request, path)) {
            ApiError body = new ApiError(status.value(), status.getReasonPhrase(), code, message, path, Instant.now(),
                    fieldErrors);
            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                    .cacheControl(CacheControl.noStore()).body(body);
        }
        WebContext context = new WebContext(JakartaServletWebApplication.buildApplication(request.getServletContext())
                .buildExchange(request, response), Locale.ENGLISH);
        context.setVariable("status", status.value());
        context.setVariable("error", status.getReasonPhrase());
        context.setVariable("message", message);
        context.setVariable("csrfToken", csrf.token());
        context.setVariable("activeNav", "");
        String html = templates.process("error", context);
        return ResponseEntity.status(status).contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore()).body(html);
    }

    static boolean wantsJson(HttpServletRequest request, String path) {
        if (path != null && (path.startsWith("/api/") || path.equals("/api") || path.startsWith("/actuator"))) {
            return true;
        }
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("application/json") && !accept.contains("text/html");
    }

    static String originalPath(HttpServletRequest request) {
        Object forwarded = request.getAttribute("jakarta.servlet.error.request_uri");
        return forwarded instanceof String s ? s : request.getRequestURI();
    }
}
