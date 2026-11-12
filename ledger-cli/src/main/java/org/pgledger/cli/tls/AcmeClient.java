package org.pgledger.cli.tls;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.pgledger.cli.CliMessages;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.shredzone.acme4j.Account;
import org.shredzone.acme4j.AccountBuilder;
import org.shredzone.acme4j.Authorization;
import org.shredzone.acme4j.Certificate;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.Problem;
import org.shredzone.acme4j.Session;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.exception.AcmeLazyLoadingException;
import org.shredzone.acme4j.exception.AcmeProtocolException;
import org.shredzone.acme4j.exception.AcmeServerException;
import org.springframework.stereotype.Component;

/**
 * The one place that talks ACME (RFC 8555) to a certificate authority, with the library acme4j: the account, an order for the
 * host names, the HTTP-01 proof of each name, the finalization with a certificate request for a new key, and the download of
 * the chain. The proof is a token file below the web root, which the web server serves on port 80 and which is removed again
 * when the order is over, whatever its result. It writes nothing of the result: the certificate and its key are handed back
 * to the caller, who puts them in place ({@link TlsIssuer}), so a failure at any step has changed no certificate file.
 *
 * <p>A failure is a {@code TLS_ISSUE_FAILED} that names the step and the kind of the failure (for a refusal of the authority
 * the type of its problem document, a URN of RFC 8555 section 6.7), never a key, a token or a response body.
 */
@Component
class AcmeClient {

    /** How long each wait for the authority may take (it honors Retry-After in between). */
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private final TlsFiles files;
    private final CliOutput output;
    private final CliMessages messages;

    AcmeClient(TlsFiles files, CliOutput output, CliMessages messages) {
        this.files = files;
        this.output = output;
        this.messages = messages;
    }

    /**
     * What an order gives: the chain as PEM text (the certificate of the host names first) and the key it was made for.
     */
    record Issued(String chainPem, KeyPair keys) {
    }

    /** Keeps the key of an account the authority has accepted; a failure is a refusal of the command (the implementation throws it). */
    @FunctionalInterface
    interface AccountStore {

        void keep(KeyPair account);
    }

    /**
     * @param account      the key of the account
     * @param onlyExisting true for a renewal: the authority is asked for the account of the key and creates none (so no
     *                     terms of service are agreed to here); false for the issue: the account is created when it is new
     * @throws FailureException TLS_CHALLENGE_TOKEN for a token that is no plain token, TLS_FILES_FAILED when a token file cannot
     *         be written, TLS_ISSUE_FAILED for every other failure
     */
    Issued obtain(TlsRequest request, KeyPair account, boolean onlyExisting, AccountStore store) {
        List<Path> tokenFiles = new ArrayList<>();
        String step = "directory";
        try {
            Session session = new Session(request.acmeDirectory());
            step = "account";
            Account login = accountOf(session, account, request, onlyExisting);
            // the key is kept as soon as the authority has the account, so that a later failure does not leave an account
            // behind that a second run cannot find (and a run that never reached the authority leaves no key)
            store.keep(account);
            step = "order";
            Order order = login.newOrder().domains(request.hosts()).create();
            step = "challenge";
            for (Authorization authorization : order.getAuthorizations()) {
                prove(authorization, request, tokenFiles);
            }
            step = "order";
            Status ready = order.waitUntilReady(TIMEOUT);
            if (ready != Status.READY) {
                throw new FailureException(ErrorCode.TLS_ISSUE_FAILED, step, "order " + ready);
            }
            step = "finalize";
            KeyPair keys = files.newKeyPair();
            order.execute(keys);
            Status done = order.waitForCompletion(TIMEOUT);
            if (done != Status.VALID) {
                throw new FailureException(ErrorCode.TLS_ISSUE_FAILED, step, "order " + done);
            }
            step = "certificate";
            Certificate certificate = order.getCertificate();
            StringWriter chain = new StringWriter();
            certificate.writeCertificate(chain);
            return new Issued(chain.toString(), keys);
        } catch (AcmeException | AcmeProtocolException | AcmeLazyLoadingException e) {
            throw new FailureException(ErrorCode.TLS_ISSUE_FAILED, e, step, kind(e));
        } catch (IOException e) {
            throw new FailureException(ErrorCode.TLS_ISSUE_FAILED, e, step, e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FailureException(ErrorCode.TLS_ISSUE_FAILED, e, step, "Interrupted");
        } finally {
            remove(tokenFiles);
        }
    }

    private static Account accountOf(Session session, KeyPair keys, TlsRequest request, boolean onlyExisting)
            throws AcmeException {
        AccountBuilder builder = new AccountBuilder().useKeyPair(keys);
        if (onlyExisting) {
            builder.onlyExisting();
        } else {
            if (request.email() != null) {
                builder.addEmail(request.email());
            }
            if (request.agreeToTerms()) {
                builder.agreeToTermsOfService();
            }
        }
        return builder.create(session);
    }

    /** The proof of one host name: the token file, the trigger, the wait for the verdict of the authority. */
    private void prove(Authorization authorization, TlsRequest request, List<Path> tokenFiles)
            throws AcmeException, IOException, InterruptedException {
        if (authorization.getStatus() == Status.VALID) {
            // the authority remembers a proof of this account (RFC 8555 section 7.1.4): nothing to show
            return;
        }
        Http01Challenge challenge = authorization.findChallenge(Http01Challenge.class)
                .orElseThrow(() -> new FailureException(ErrorCode.TLS_ISSUE_FAILED, "challenge", "no http-01 challenge offered"));
        // the raw token, so that this check is the guard and not the library's: it becomes a file name
        String token = challenge.getJSON().get("token").asString();
        if (!TlsFiles.isPlainToken(token)) {
            throw new FailureException(ErrorCode.TLS_CHALLENGE_TOKEN);
        }
        Path file = files.publishToken(request.webroot(), token, challenge.getAuthorization())
                .orElseThrow(() -> new FailureException(ErrorCode.TLS_CHALLENGE_TOKEN));
        tokenFiles.add(file);
        challenge.trigger();
        Status verdict = authorization.waitForCompletion(TIMEOUT);
        if (verdict != Status.VALID) {
            String reason = challenge.getError().map(Problem::getType).map(Object::toString).orElse("authorization " + verdict);
            throw new FailureException(ErrorCode.TLS_ISSUE_FAILED, "challenge", reason);
        }
    }

    private void remove(List<Path> tokenFiles) {
        for (Path file : tokenFiles) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                output.info(messages.get("tls.cleanup-failed", file.toString(), e.getClass().getSimpleName()));
            }
        }
    }

    /** What went wrong, without a value of the request or of the answer: the problem type of a refusal, else the kind of the failure. */
    private static String kind(Exception e) {
        return e instanceof AcmeServerException refused ? refused.getType().toString() : e.getClass().getSimpleName();
    }
}
