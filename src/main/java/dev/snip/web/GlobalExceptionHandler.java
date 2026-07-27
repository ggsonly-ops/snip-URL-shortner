package dev.snip.web;

import dev.snip.dto.Dtos.ErrorResponse;
import dev.snip.exception.AliasUnavailableException;
import dev.snip.exception.ForbiddenException;
import dev.snip.exception.InvalidUrlException;
import dev.snip.exception.LinkNotFoundException;
import dev.snip.exception.PasswordRequiredException;
import dev.snip.id.SnowflakeIdGenerator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(LinkNotFoundException.class)
    public ResponseEntity<?> notFound(LinkNotFoundException e, HttpServletRequest req) {
        if (wantsHtml(req)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.TEXT_HTML)
                    .body(notFoundPage());
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("LINK_NOT_FOUND", "That link does not exist, or it has expired"));
    }

    @ExceptionHandler(InvalidUrlException.class)
    public ResponseEntity<ErrorResponse> invalidUrl(InvalidUrlException e) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("INVALID_URL", e.getMessage()));
    }

    @ExceptionHandler(AliasUnavailableException.class)
    public ResponseEntity<ErrorResponse> aliasTaken(AliasUnavailableException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("ALIAS_UNAVAILABLE", e.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> forbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of("FORBIDDEN", e.getMessage()));
    }

    @ExceptionHandler(PasswordRequiredException.class)
    public ResponseEntity<ErrorResponse> passwordRequired(PasswordRequiredException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of("PASSWORD_REQUIRED", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException e) {
        List<String> details = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .toList();
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("VALIDATION_FAILED", "Request body failed validation", null, details));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> constraint(ConstraintViolationException e) {
        List<String> details = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .toList();
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("VALIDATION_FAILED", "Request failed validation", null, details));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("MALFORMED_REQUEST", "Request body could not be parsed as JSON"));
    }

    /**
     * Clock skew large enough to make Snowflake unsafe. Answered as 503 rather than 500
     * because it is a transient host condition that a load balancer should route around,
     * not a bug in the request.
     */
    @ExceptionHandler(SnowflakeIdGenerator.ClockMovedBackwardsException.class)
    public ResponseEntity<ErrorResponse> clockSkew(SnowflakeIdGenerator.ClockMovedBackwardsException e) {
        log.error("Refusing to generate ids: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "5")
                .body(ErrorResponse.of("CLOCK_SKEW", "This instance cannot mint ids right now; retry shortly"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception e, HttpServletRequest req) {
        log.error("Unhandled exception for {} {}", req.getMethod(), req.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR", "Something went wrong"));
    }

    private static boolean wantsHtml(HttpServletRequest req) {
        String accept = req.getHeader("Accept");
        return !req.getRequestURI().startsWith("/api/")
                && accept != null && accept.contains("text/html");
    }

    private static String notFoundPage() {
        return """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Link not found</title>
                <style>
                  :root{color-scheme:light dark}
                  body{font:16px/1.6 system-ui,sans-serif;display:grid;place-items:center;
                       min-height:100vh;margin:0;text-align:center;background:Canvas;color:CanvasText}
                  h1{font-size:3rem;margin:0}
                  p{opacity:.7}
                  a{color:inherit}
                </style></head>
                <body><div>
                  <h1>404</h1>
                  <p>That short link does not exist, or it has expired.</p>
                  <p><a href="/">Shorten a new one</a></p>
                </div></body></html>
                """;
    }
}
