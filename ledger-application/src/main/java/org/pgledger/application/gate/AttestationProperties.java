package org.pgledger.application.gate;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of the author attestation ({@code ledger.attestation.*}). A value that does not bind stops the startup.
 *
 * @param window how far the issue time of an attestation may be from the server time, in either direction. The hook
 *               issues a token for every call, so the window only has to cover the clock difference between the
 *               machine of the hook and the server plus the time a call takes. A token that is replayed inside the
 *               window cannot write anything new (it is bound to one request id, and a request id carries one
 *               payload), so the window is not the replay protection
 */
@Validated
@ConfigurationProperties(prefix = "ledger.attestation")
public record AttestationProperties(@DefaultValue(AttestationProperties.DEFAULT_WINDOW) Duration window) {

    /** ISO-8601, which {@link Duration#parse} and the Boot binder both read. */
    public static final String DEFAULT_WINDOW = "PT5M";

    public AttestationProperties {
        if (window == null || window.isZero() || window.isNegative() || window.toSeconds() < 1) {
            throw new IllegalArgumentException("ledger.attestation.window must be at least one second");
        }
    }
}
