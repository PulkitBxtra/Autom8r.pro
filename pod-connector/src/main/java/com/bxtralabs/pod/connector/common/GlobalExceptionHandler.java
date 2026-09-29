package com.bxtralabs.pod.connector.common;

import com.bxtralabs.pod.connector.connections.ConnectionsNotConfiguredException;
import io.jsonwebtoken.JwtException;
import com.bxtralabs.pod.connector.connections.ConnectionNeedsReauthException;
import com.bxtralabs.pod.connector.connections.TokenRefreshException;
import com.bxtralabs.pod.connector.internal.InternalAuth;
import com.bxtralabs.pod.connector.options.OptionsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    // Bad signature, malformed or expired login token.
    @ExceptionHandler(JwtException.class)
    public ResponseEntity<Map<String, String>> handleBadToken(JwtException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid or expired login token"));
    }

    @ExceptionHandler(InternalAuth.InternalAuthException.class)
    public ResponseEntity<Map<String, String>> handleInternalAuth(InternalAuth.InternalAuthException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", ex.getMessage()));
    }

    // The provider no longer accepts the connection; only the user can fix it by reconnecting.
    @ExceptionHandler(ConnectionNeedsReauthException.class)
    public ResponseEntity<Map<String, String>> handleNeedsReauth(ConnectionNeedsReauthException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage(), "code", "needs_reauth"));
    }

    // Refresh failed for a reason that may pass (provider down, rate limited): try again later.
    @ExceptionHandler(TokenRefreshException.class)
    public ResponseEntity<Map<String, String>> handleRefresh(TokenRefreshException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", ex.getMessage(), "code", "temporary"));
    }

    // An app couldn't list a step setting's choices (missing permission, app down).
    @ExceptionHandler(OptionsException.class)
    public ResponseEntity<Map<String, String>> handleOptions(OptionsException ex) {
        return ResponseEntity.status(ex.status()).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, String>> handleConflict(ConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(ConnectionsNotConfiguredException.class)
    public ResponseEntity<Map<String, String>> handleNotConfigured(ConnectionsNotConfiguredException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, String>> handleMissingHeader(MissingRequestHeaderException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", "Missing " + ex.getHeaderName() + " header"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fieldError ->
                errors.put(fieldError.getField(), fieldError.getDefaultMessage()));
        return ResponseEntity.badRequest().body(errors);
    }
}
