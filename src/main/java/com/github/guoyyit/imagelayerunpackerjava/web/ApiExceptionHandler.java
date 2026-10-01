package com.github.guoyyit.imagelayerunpackerjava.web;

import com.github.guoyyit.imagelayerunpackerjava.layer.UnpackException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Map;

/**
 * Maps service exceptions to stable HTTP error responses.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(UnpackException.class)
    public ResponseEntity<Map<String, String>> unpackFailed(UnpackException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "UNPACK_FAILED", "message", String.valueOf(ex.getMessage())));
    }

    @ExceptionHandler(NoSuchFileException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchFileException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "NOT_FOUND", "message", String.valueOf(ex.getMessage())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "BAD_REQUEST", "message", String.valueOf(ex.getMessage())));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "CONFLICT", "message", String.valueOf(ex.getMessage())));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> ioError(IOException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "IO_ERROR", "message", String.valueOf(ex.getMessage())));
    }
}
