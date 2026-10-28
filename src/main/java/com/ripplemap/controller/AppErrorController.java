package com.ripplemap.controller;

import com.ripplemap.exception.ErrorResponder;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

/** Handles errors raised outside Spring MVC handlers (container errors), using the same error formats. */
@Controller
public class AppErrorController implements ErrorController {

    private final ErrorResponder responder;

    public AppErrorController(ErrorResponder responder) {
        this.responder = responder;
    }

    @RequestMapping("/error")
    public ResponseEntity<?> error(HttpServletRequest request, HttpServletResponse response) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        HttpStatus status = HttpStatus.resolve(code instanceof Integer i ? i : 500);
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String message = switch (status) {
            case NOT_FOUND -> "The requested page does not exist";
            case PAYLOAD_TOO_LARGE -> "The request is too large";
            case BAD_REQUEST -> "The request could not be understood";
            case FORBIDDEN -> "Access denied";
            default -> status.is5xxServerError() ? "An unexpected error occurred" : status.getReasonPhrase();
        };
        return responder.respond(status, status.name(), message, List.of(), request, response);
    }
}
