package org.pgledger.hook;

import tools.jackson.databind.JsonNode;

/** What the hook process reports back to the host. */
sealed interface Response {

    /** Nothing on stdout, exit 0: the host carries on unchanged. */
    record Silent() implements Response {}

    /** One JSON document on stdout, exit 0. */
    record Document(JsonNode body) implements Response {}

    /** A message on stderr and exit 2 (for Claude Code: the call is blocked). */
    record Failure(String message) implements Response {}
}
