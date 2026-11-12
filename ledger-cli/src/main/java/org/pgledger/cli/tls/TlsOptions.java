package org.pgledger.cli.tls;

import java.util.Set;
import org.pgledger.cli.deploy.DeployOptions;

/** The names of the options of {@code tls-issue} and {@code tls-renew} ({@code --name=value}); the host names and the name of the instance are those of {@code render-deploy}. */
public final class TlsOptions {

    /** The web root the token files of the HTTP-01 proof are written below (default: the web root of the instance). */
    public static final String WEBROOT = "webroot";
    /** The directory of the account key and of the certificate files (default: the TLS directory of the instance). */
    public static final String TLS_DIR = "tls-dir";
    /** Optional contact address of a new account ({@code tls-issue}). */
    public static final String ACME_EMAIL = "acme-email";
    /** A flag: the operator has read the terms of service of the certificate authority and agrees to them ({@code tls-issue}). */
    public static final String AGREE_TOS = "agree-tos";
    /** A certificate that expires within this many days is renewed ({@code tls-renew}). */
    public static final String RENEW_BEFORE_DAYS = "renew-before-days";

    /** The production directory of Let's Encrypt, the one default of the product: the option {@code --acme-directory} takes any other directory of RFC 8555. */
    public static final String DEFAULT_DIRECTORY = "https://acme-v02.api.letsencrypt.org/directory";
    public static final int DEFAULT_RENEW_BEFORE_DAYS = 30;
    /** The most days a threshold may be: a certificate is never valid for ten years. */
    static final int MAX_RENEW_BEFORE_DAYS = 3650;

    /** The options of {@code tls-issue}. */
    public static final Set<String> ISSUE = Set.of(DeployOptions.MCP_HOST, DeployOptions.ADMIN_HOST, DeployOptions.NAME, WEBROOT,
            TLS_DIR, DeployOptions.ACME_DIRECTORY, ACME_EMAIL, AGREE_TOS);
    /** The options of {@code tls-renew}. */
    public static final Set<String> RENEW = Set.of(DeployOptions.MCP_HOST, DeployOptions.ADMIN_HOST, DeployOptions.NAME, WEBROOT,
            TLS_DIR, DeployOptions.ACME_DIRECTORY, RENEW_BEFORE_DAYS);

    private TlsOptions() {
    }
}
