package dev.snip.exception;

/** The link is password protected and the caller supplied no password, or the wrong one. */
public class PasswordRequiredException extends RuntimeException {
    private final String code;
    private final boolean attempted;

    public PasswordRequiredException(String code, boolean attempted) {
        super(attempted ? "Incorrect password" : "This link is password protected");
        this.code = code;
        this.attempted = attempted;
    }

    public String code() {
        return code;
    }

    /** True when a password was supplied but did not match, false when none was supplied. */
    public boolean attempted() {
        return attempted;
    }
}
