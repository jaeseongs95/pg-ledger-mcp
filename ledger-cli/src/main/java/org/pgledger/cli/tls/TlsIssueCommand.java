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
 * {@code tls-issue --mcp-host=<host> --admin-host=<host> [...]}: orders a certificate for the two public host names from a
 * certificate authority that speaks ACME (RFC 8555), with the HTTP-01 proof, and writes {@code fullchain.pem} and
 * {@code privkey.pem} into the TLS directory ({@link TlsIssuer}). The terms of service of the authority are agreed to with
 * {@code --agree-tos} and in no other way. It needs no database and prints no key.
 */
@Component
public class TlsIssueCommand implements Command {

    /** The options of the command; the documentation test reads this set, so an option cannot be added undocumented. */
    public static final Set<String> OPTIONS = TlsOptions.ISSUE;

    private final TlsRequestReader requests;
    private final TlsIssuer issuer;
    private final TlsFiles files;
    private final CliOutput output;
    private final CliMessages messages;

    TlsIssueCommand(TlsRequestReader requests, TlsIssuer issuer, TlsFiles files, CliOutput output, CliMessages messages) {
        this.requests = requests;
        this.issuer = issuer;
        this.files = files;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public CliCommand name() {
        return CliCommand.TLS_ISSUE;
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
        TlsRequest request = requests.read(arguments, true);
        TlsIssuer.Outcome outcome = issuer.issue(request);
        output.info(messages.get("tls.issued", String.join(", ", request.hosts()), files.chain(request.directory()).toString(),
                files.privateKey(request.directory()).toString(), outcome.notAfter().toString()));
    }
}
