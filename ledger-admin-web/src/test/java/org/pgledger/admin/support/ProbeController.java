package org.pgledger.admin.support;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A route that fails with an unexpected exception (to look at the answer of a server error). Test classpath only. */
@RestController
public class ProbeController {

    /** The secret-looking text that a server error must never show. */
    public static final String INTERNAL_DETAIL = "internal-detail-select-from-secret-table";

    @GetMapping("/probe/fail")
    String fail() {
        throw new IllegalStateException(INTERNAL_DETAIL);
    }
}
