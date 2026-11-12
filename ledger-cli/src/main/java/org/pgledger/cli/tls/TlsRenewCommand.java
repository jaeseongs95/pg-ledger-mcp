package org.pgledger.cli.tls;

import java.util.List;
import java.util.Set;
import org.pgledger.cli.CliCommand;
import org.pgledger.cli.CliMessages;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.Command;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.UsageException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code tls-renew --mcp-host=<host> --admin-host=<host> [...]}: the certificate in the TLS directory is renewed when it
 * expires within {@code --renew-before-days} (default 30) or does not name exactly the two host names; otherwise nothing is
 * ordered and the output says so ({@link TlsIssuer#renew}). It is what the timer of {@code render-deploy --tls-acme} runs. It
 * never creates an account, so it needs no terms option, and it needs no database.
 */
@Component
public class TlsRenewCommand implements Command {

    /** The options of the command; the documentation test reads this set, so an option cannot be added undocumented. */
    public static final Set<String> OPTIONS = TlsOptions.RENEW;

    private final TlsRequestReader requests;
    private final TlsIssuer issuer;
    private final TlsFiles files;
    private final CliOutput output;
    private final CliMessages messages;

    TlsRenewCommand(TlsRequestReader requests, TlsIssuer issuer, TlsFiles files, CliOutput output, CliMessages messages) {
        this.requests = requests;
        this.issuer = issuer;
        this.files = files;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public CliCommand name() {
        return CliCommand.TLS_RENEW;
    }

    @Override
    public Set<String> options() {
        return OPTIONS;
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (!operands.isEmpty()) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        TlsRequest request = requests.read(arguments, false);
        TlsIssuer.Outcome outcome = issuer.renew(request);
        String hosts = String.join(", ", request.hosts());
        if (outcome.result() == TlsIssuer.Result.NOT_DUE) {
            output.info(messages.get("tls.not-due", hosts, outcome.notAfter().toString(),
                    String.valueOf(request.renewBeforeDays())));
        } else {
            output.info(messages.get("tls.issued", hosts, files.chain(request.directory()).toString(),
                    files.privateKey(request.directory()).toString(), outcome.notAfter().toString()));
        }
    }
}
