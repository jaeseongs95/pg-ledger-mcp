package org.pgledger.hook;

import java.util.List;

/** What the hook process reports back to the host (or, for {@code session-key}, to the owner). */
sealed interface Response {

    /** Nothing on stdout, exit 0: the host carries on unchanged. */
    record Silent() implements Response {}

    /** One JSON document on stdout, exit 0. */
    record Document(HostDocument body) implements Response {}

    /** Text lines on stdout, one per element, exit 0: the answer of the {@code session-key} command. */
    record Lines(List<String> lines) implements Response {}

    /** A message on stderr and exit 2 (for Claude Code: the call is blocked). */
    record Failure(String message) implements Response {}

    /** Stdin could not be read: logged with its cause, exit 2. */
    record InputFailure(java.io.IOException cause) implements Response {}
}
