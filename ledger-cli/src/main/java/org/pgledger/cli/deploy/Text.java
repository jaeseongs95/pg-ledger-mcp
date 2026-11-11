package org.pgledger.cli.deploy;

/** What the renderers of the deployment files share: the generated-file notice and how a line is written. */
final class Text {

    static final String GENERATED = "# Rendered by `ledger-cli render-deploy` (deploy/install.sh). Do not edit: the next run replaces this file.";

    private Text() {
    }

    /** One line with {@code \n} as the line end, whatever the platform is: the output is the same everywhere. */
    static void line(StringBuilder text, String line) {
        text.append(line).append('\n');
    }

    /** A YAML single-quoted scalar; the values are validated plain text ({@link DeployParameters}), so nothing is escaped. */
    static String quoted(String value) {
        return "'" + value + "'";
    }
}
