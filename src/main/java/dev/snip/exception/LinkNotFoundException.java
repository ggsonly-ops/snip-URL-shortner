package dev.snip.exception;

public class LinkNotFoundException extends RuntimeException {
    private final String code;

    public LinkNotFoundException(String code) {
        super("No live link for code '" + code + "'");
        this.code = code;
    }

    public String code() {
        return code;
    }
}
