package org.pgledger.cli.tls;

import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.pgledger.cli.CliOptions;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.UsageException;
import org.pgledger.cli.deploy.DeployForms;
import org.pgledger.cli.deploy.DeployLayout;
import org.pgledger.cli.deploy.DeployOptions;
import org.pgledger.cli.seed.InstallDefaults;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * Reads the options of {@code tls-issue} and {@code tls-renew} and refuses what cannot be used before anything is read from the
 * host or sent to a certificate authority. The host names and the name of the instance have the forms of {@code render-deploy};
 * the paths are plain absolute paths (a Linux path or a drive path), and when they are not given they are those of the layout of
 * the instance (the Linux installation: the commands themselves run anywhere). A refusal names the option and never the value.
 */
@Component
final class TlsRequestReader {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._+-]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?");
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");
    private static final int MAX_URL = 600;

    private final InstallDefaults defaults;

    TlsRequestReader(InstallDefaults defaults) {
        this.defaults = defaults;
    }

    /**
     * @param issue whether the request is for {@code tls-issue} (it reads the e-mail address and the terms flag) or for
     *              {@code tls-renew} (it reads the threshold)
     * @throws UsageException MISSING_ARGUMENT for a host name that is absent, INVALID_DEPLOY_VALUE for a host name, the name
     *         or a path that is not plain, INVALID_OPTION_VALUE for the directory URL, the address or the threshold
     */
    TlsRequest read(ApplicationArguments arguments, boolean issue) {
        // the names are those of the deployment: a comma list for each service, the admin web with no public name needs none
        List<String> mcpHosts = DeployForms.requireHosts(CliOptions.required(arguments, DeployOptions.MCP_HOST), DeployOptions.MCP_HOST);
        List<String> adminHosts = CliOptions.value(arguments, DeployOptions.ADMIN_HOST)
                .map(value -> DeployForms.requireHosts(value, DeployOptions.ADMIN_HOST)).orElse(List.of());
        if (!Collections.disjoint(mcpHosts, adminHosts)) {
            // the same rule as the deployment: a name is served by one of the two services
            throw new UsageException(ErrorCode.INVALID_DEPLOY_VALUE, DeployOptions.ADMIN_HOST);
        }
        String name = DeployForms.requireName(CliOptions.value(arguments, DeployOptions.NAME).orElseGet(defaults::instanceName),
                DeployOptions.NAME);
        DeployLayout layout = DeployLayout.linux(name);
        Path webroot = path(CliOptions.value(arguments, TlsOptions.WEBROOT).orElse(layout.acmeWebroot()), TlsOptions.WEBROOT);
        Path directory = path(CliOptions.value(arguments, TlsOptions.TLS_DIR).orElse(layout.tlsDirectory()), TlsOptions.TLS_DIR);
        URI acme = directory(CliOptions.value(arguments, DeployOptions.ACME_DIRECTORY).orElse(TlsOptions.DEFAULT_DIRECTORY));
        String email = null;
        boolean agree = false;
        int days = TlsOptions.DEFAULT_RENEW_BEFORE_DAYS;
        if (issue) {
            // the request says "no address" with a null component, like the command records of the application services
            String given = CliOptions.valueOrNull(arguments, TlsOptions.ACME_EMAIL);
            email = given == null ? null : email(given);
            agree = CliOptions.flag(arguments, TlsOptions.AGREE_TOS);
        } else {
            days = days(CliOptions.integerOrNull(arguments, TlsOptions.RENEW_BEFORE_DAYS));
        }
        return new TlsRequest(Stream.concat(mcpHosts.stream(), adminHosts.stream()).toList(), webroot, directory, acme, email, agree, days);
    }

    private static Path path(String value, String option) {
        try {
            String plain = DeployForms.isPath(value, DeployForms.POSIX_PATH) ? value
                    : DeployForms.requirePath(value, DeployForms.WINDOWS_PATH, option);
            return Path.of(plain);
        } catch (InvalidPathException e) {
            throw new UsageException(ErrorCode.INVALID_DEPLOY_VALUE, e, option);
        }
    }

    /**
     * The directory URL of the certificate authority: https, or http for a certificate authority on this host (a test, never
     * a network: the requests are signed but the answers would be open to change), without a user part, a fragment or an escape.
     */
    static URI directory(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            boolean https = "https".equals(scheme);
            boolean loopbackHttp = "http".equals(scheme) && uri.getHost() != null
                    && LOOPBACK.contains(uri.getHost().toLowerCase(Locale.ROOT));
            if (value.length() > MAX_URL || !(https || loopbackHttp) || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null || value.indexOf('%') >= 0) {
                throw new UsageException(ErrorCode.INVALID_OPTION_VALUE);
            }
            return uri;
        } catch (java.net.URISyntaxException e) {
            throw new UsageException(ErrorCode.INVALID_OPTION_VALUE, e);
        }
    }

    private static String email(String value) {
        if (value.length() > 254 || !EMAIL.matcher(value).matches()) {
            throw new UsageException(ErrorCode.INVALID_OPTION_VALUE);
        }
        return value;
    }

    private static int days(Integer value) {
        if (value == null) {
            return TlsOptions.DEFAULT_RENEW_BEFORE_DAYS;
        }
        if (value < 1 || value > TlsOptions.MAX_RENEW_BEFORE_DAYS) {
            throw new UsageException(ErrorCode.INVALID_OPTION_VALUE);
        }
        return value;
    }
}
