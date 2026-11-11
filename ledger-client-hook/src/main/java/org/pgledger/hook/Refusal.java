package org.pgledger.hook;

/** Why a ledger call is denied. The wording is shown to the user by the host. */
enum Refusal {
    MALFORMED_INPUT("ledger: hook input must be valid JSON"),
    ARGUMENTS_NOT_OBJECT("ledger: tool arguments must be an object"),
    ARGUMENTS_NOT_JSON_OBJECT("ledger: Arguments must contain a JSON object"),
    RESERVED_FIELD("ledger: _hostAttestation is reserved for the host hook; omit it from the tool arguments"),
    HOST_ID_INVALID("ledger: a valid host session id is required (1 to 128 characters of A-Z a-z 0-9 . _ -)");

    private final String message;

    Refusal(String message) {
        this.message = message;
    }

    String message() {
        return message;
    }
}
