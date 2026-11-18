package dev.cimiento.core.web;

import dev.cimiento.core.service.ApiException;
import dev.cimiento.core.web.ApiDtos.ErrorView;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps errors to JSON. Messages never echo request bodies. */
@RestControllerAdvice
public class ApiErrorHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorView> api(ApiException e) {
        return ResponseEntity.status(e.getStatus())
                .body(new ErrorView(e.getStatus().name().toLowerCase(), e.getMessage(), e.getDetails()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorView> badRequest(Exception e) {
        String message = e instanceof MethodArgumentNotValidException m
                ? m.getBindingResult().getFieldErrors().stream()
                        .map(f -> f.getField() + " " + f.getDefaultMessage()).sorted().toList().toString()
                : "malformed request";
        return ResponseEntity.badRequest().body(new ErrorView("bad_request", message, null));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorView> conflict(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorView("conflict", "concurrent request conflicts with stored data; retry", null));
    }
}
