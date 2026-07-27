package dev.snip.exception;

/** The submitted URL was malformed, used a disallowed scheme, or pointed somewhere we refuse to shorten. */
public class InvalidUrlException extends RuntimeException {
    public InvalidUrlException(String message) {
        super(message);
    }
}
