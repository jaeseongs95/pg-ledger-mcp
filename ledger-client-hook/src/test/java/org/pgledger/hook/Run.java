package org.pgledger.hook;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/** The result of one hook invocation: exit code and the bytes on stdout and stderr. */
record Run(int exit, byte[] stdout, byte[] stderr) {

    /** The captured bytes become text only for the assertion; no file or stream API applies to an in-memory byte[]. */
    String out() {
        return new String(stdout, StandardCharsets.UTF_8);
    }

    String err() {
        return new String(stderr, StandardCharsets.UTF_8);
    }

    /** The JSON document on stdout; empty if nothing was written. */
    Optional<JsonNode> document() {
        return stdout.length == 0 ? Optional.empty() : Optional.of(Json.MAPPER.readTree(stdout));
    }

    JsonNode json() {
        return document().orElseThrow(() -> new AssertionError("the hook wrote nothing on stdout"));
    }
}
