package dev.snip.exception;

/** The caller's API key does not own the link they are trying to modify. */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
