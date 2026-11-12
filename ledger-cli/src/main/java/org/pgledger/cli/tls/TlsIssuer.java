package org.pgledger.cli.tls;

import java.io.IOException;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.cli.UsageException;
import org.springframework.stereotype.Component;

/**
 * What {@code tls-issue} and {@code tls-renew} do: decide whether a certificate is to be ordered, order it ({@link AcmeClient}),
 * and put it in place. A certificate is put in place only when the authority has delivered a readable certificate, and then
 * atomically ({@link TlsFiles#writeCertificate}): a failure at any step, the order or the write, leaves the files of the
 * earlier certificate as they were. The account key is created with the account, stored owner-only, and never printed.
 */
@Component
class TlsIssuer {

    /** What was done: a certificate was obtained, or the one in place is not due. */
    enum Result {
        ISSUED, NOT_DUE
    }

    /**
     * @param result   what was done
     * @param notAfter the end of the validity of the certificate in place now
     */
    record Outcome(Result result, Instant notAfter) {
    }

    private final AcmeClient client;
    private final TlsFiles files;
    private final Clock clock;

    TlsIssuer(AcmeClient client, TlsFiles files, Clock clock) {
        this.client = client;
        this.files = files;
        this.clock = clock;
    }

    /**
     * Orders a certificate for the host names, whatever the certificate in place is.
     *
     * @throws UsageException TLS_TERMS_NOT_AGREED when there is no account yet and the terms were not agreed to (nothing is sent)
     * @throws FailureException TLS_ISSUE_FAILED and the others of {@link AcmeClient#obtain}, TLS_FILES_FAILED
     */
    Outcome issue(TlsRequest request) {
        Path directory = request.directory();
        boolean known = files.hasAccountKey(directory);
        if (!known && !request.agreeToTerms()) {
            throw new UsageException(ErrorCode.TLS_TERMS_NOT_AGREED);
        }
        KeyPair account;
        try {
            account = known ? files.readAccountKey(directory) : files.newKeyPair();
        } catch (IOException e) {
            throw new FailureException(ErrorCode.TLS_FILES_FAILED, e, files.accountKey(directory).toString(), kind(e));
        }
        return order(request, account, false, known ? keys -> {
        } : keys -> keep(directory, keys));
    }

    /**
     * Orders a new certificate when the one in place expires within the threshold or does not name exactly the host names, and
     * otherwise orders nothing. It never creates an account.
     *
     * @throws FailureException TLS_NO_CERTIFICATE, TLS_NO_ACCOUNT (before anything is sent), TLS_ISSUE_FAILED and the others of
     *         {@link AcmeClient#obtain}, TLS_FILES_FAILED
     */
    Outcome renew(TlsRequest request) {
        Path directory = request.directory();
        X509Certificate current = current(directory).orElseThrow(
                () -> new FailureException(ErrorCode.TLS_NO_CERTIFICATE, files.chain(directory).toString()));
        if (!files.hasAccountKey(directory)) {
            throw new FailureException(ErrorCode.TLS_NO_ACCOUNT, files.accountKey(directory).toString());
        }
        Instant notAfter = current.getNotAfter().toInstant();
        boolean sameNames = Objects.equals(files.names(current), new TreeSet<>(request.hosts()));
        // due: it expires within the threshold (exactly at the threshold is within), or it is for other names than the hosts
        boolean due = !sameNames || !notAfter.isAfter(clock.instant().plus(Duration.ofDays(request.renewBeforeDays())));
        if (!due) {
            return new Outcome(Result.NOT_DUE, notAfter);
        }
        KeyPair account;
        try {
            account = files.readAccountKey(directory);
        } catch (IOException e) {
            throw new FailureException(ErrorCode.TLS_FILES_FAILED, e, files.accountKey(directory).toString(), kind(e));
        }
        return order(request, account, true, keys -> {
        });
    }

    private Optional<X509Certificate> current(Path directory) {
        try {
            return files.readCertificate(directory);
        } catch (IOException e) {
            throw new FailureException(ErrorCode.TLS_FILES_FAILED, e, files.chain(directory).toString(), kind(e));
        }
    }

    private Outcome order(TlsRequest request, KeyPair account, boolean onlyExisting, AcmeClient.AccountStore store) {
        Path directory = request.directory();
        AcmeClient.Issued issued = client.obtain(request, account, onlyExisting, store);
        try {
            // a certificate the authority sent that cannot be read, or that is for other names, replaces nothing
            X509Certificate certificate = files.firstCertificate(issued.chainPem()).orElseThrow(
                    () -> new FailureException(ErrorCode.TLS_ISSUE_FAILED, "certificate", "unreadable certificate"));
            Set<String> names = files.names(certificate);
            if (!Objects.equals(names, new TreeSet<>(request.hosts()))) {
                throw new FailureException(ErrorCode.TLS_ISSUE_FAILED, "certificate", "names differ from the order");
            }
            files.ensureDirectory(directory);
            files.writeCertificate(directory, issued.chainPem(), issued.keys().getPrivate());
            return new Outcome(Result.ISSUED, certificate.getNotAfter().toInstant());
        } catch (IOException e) {
            throw new FailureException(ErrorCode.TLS_FILES_FAILED, e, directory.toString(), kind(e));
        }
    }

    private void keep(Path directory, KeyPair keys) {
        try {
            files.ensureDirectory(directory);
            files.writeAccountKey(directory, keys);
        } catch (IOException e) {
            throw new FailureException(ErrorCode.TLS_FILES_FAILED, e, files.accountKey(directory).toString(), kind(e));
        }
    }

    private static String kind(IOException e) {
        return e.getClass().getSimpleName();
    }
}
