package org.pgledger.application.write;

/** What every write request carries: an optional request id (the idempotency key) and the reason. */
public sealed interface WriteCommand
        permits SaveCommand, TransitionCommand, RecordFixCommand, RecordCaseCommand, LinkCommand, CorrectCommand {

    /** The idempotency key as text; absent means the server makes a fresh one (a blind retry is then a new write). */
    String requestId();

    String reason();
}
