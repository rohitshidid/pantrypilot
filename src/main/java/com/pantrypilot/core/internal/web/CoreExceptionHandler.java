package com.pantrypilot.core.internal.web;

import com.pantrypilot.core.internal.CoreException;
import java.util.Map;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Turns the core module's exceptions into {@code {"error": "..."}} answers, the same shape the AI module uses. */
@RestControllerAdvice(basePackageClasses = CoreExceptionHandler.class)
public class CoreExceptionHandler {

    @ExceptionHandler(CoreException.class)
    ResponseEntity<Map<String, String>> core(CoreException e) {
        HttpStatus status = switch (e.kind()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case CONFLICT -> HttpStatus.CONFLICT;
            case INVALID -> HttpStatus.BAD_REQUEST;
        };
        return error(status, e.getMessage());
    }

    @ExceptionHandler({
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class,
        HttpMessageNotReadableException.class
    })
    ResponseEntity<Map<String, String>> invalidRequest(Exception e) {
        return error(HttpStatus.BAD_REQUEST, "The request is missing a field or has an invalid value");
    }

    /** Still losing the race after every retry: the household is unusually busy on this item. */
    @ExceptionHandler(ConcurrencyFailureException.class)
    ResponseEntity<Map<String, String>> busy(ConcurrencyFailureException e) {
        return error(HttpStatus.CONFLICT, "Someone else changed this at the same time. Please try again.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, String>> clash(DataIntegrityViolationException e) {
        return error(HttpStatus.CONFLICT, "That clashes with something that already exists");
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
