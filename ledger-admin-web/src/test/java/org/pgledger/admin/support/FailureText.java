package org.pgledger.admin.support;

import org.springframework.boot.context.properties.bind.validation.BindValidationException;

/**
 * What a failed start says: the message of every cause, and for a failed validation of bound properties the message of
 * each violated constraint (the exception of the binder carries them in its errors, not in its own message).
 */
public final class FailureText {

    private FailureText() {
    }

    public static String of(Throwable failure) {
        StringBuilder shown = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            shown.append(cause.getMessage()).append('\n');
            if (cause instanceof BindValidationException validation) {
                validation.getValidationErrors().getAllErrors()
                        .forEach(error -> shown.append(error.getDefaultMessage()).append('\n'));
            }
        }
        return shown.toString();
    }
}
