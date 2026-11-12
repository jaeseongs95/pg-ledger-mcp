package org.pgledger.hook;

/**
 * The host id of a call as it was found in the host event: a valid {@link HostId}, or {@link Invalid} when the event had
 * none or none of the allowed form. Kept as a value (and not checked while reading the event) so that the refusals of a
 * call keep their order: the arguments and the reserved key are judged before the host id is.
 */
sealed interface HostIdentity permits HostId, HostIdentity.Invalid {

    /** No host id, a value that is not a JSON string, or text that is not of the allowed form. */
    record Invalid() implements HostIdentity {}

    /** The id if {@code text} is of the allowed form, otherwise {@link Invalid}; null (absent) gives {@link Invalid}. */
    static HostIdentity of(String text) {
        return HostId.accepts(text) ? new HostId(text) : new Invalid();
    }
}
