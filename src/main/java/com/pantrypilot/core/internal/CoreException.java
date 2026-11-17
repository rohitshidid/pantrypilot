package com.pantrypilot.core.internal;

/**
 * Every "the request cannot be done" case in the core module. The message is
 * written for the user and is safe to show; the kind picks the HTTP status.
 *
 * <p>It is a RuntimeException on purpose: Spring rolls a transaction back when
 * one escapes a {@code @Transactional} method, which is what makes a failing
 * line undo the whole proposal.
 */
public class CoreException extends RuntimeException {

    public enum Kind {
        NOT_FOUND, FORBIDDEN, CONFLICT, INVALID
    }

    private final Kind kind;

    private CoreException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public static CoreException notFound(String message) {
        return new CoreException(Kind.NOT_FOUND, message);
    }

    public static CoreException forbidden(String message) {
        return new CoreException(Kind.FORBIDDEN, message);
    }

    public static CoreException conflict(String message) {
        return new CoreException(Kind.CONFLICT, message);
    }

    public static CoreException invalid(String message) {
        return new CoreException(Kind.INVALID, message);
    }
}
