package org.pgledger.admin.web;

import org.springframework.http.HttpStatusCode;

/**
 * The {@code code} of a problem for a failure that has no business code of its own: one that Spring MVC itself raises (a
 * method that is not supported, a path that does not exist, a body that cannot be read). The tokens are those of the
 * external contract; a status that Spring's handler can answer and that has no constant of its own is {@link #HTTP_OTHER}.
 * The code is a constant of this enum, never a text built from the number of the status.
 */
enum HttpErrorCode {
    NOT_FOUND, VALIDATION_FAILED, HTTP_403, HTTP_405, HTTP_406, HTTP_413, HTTP_415, HTTP_500, HTTP_503, HTTP_OTHER;

    /** The code of the status Spring answers with. */
    static HttpErrorCode of(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> VALIDATION_FAILED;
            case 404 -> NOT_FOUND;
            case 403 -> HTTP_403;
            case 405 -> HTTP_405;
            case 406 -> HTTP_406;
            case 413 -> HTTP_413;
            case 415 -> HTTP_415;
            case 500 -> HTTP_500;
            case 503 -> HTTP_503;
            default -> HTTP_OTHER;
        };
    }
}
