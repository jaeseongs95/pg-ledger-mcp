package org.pgledger.cli.deploy;

/** What the renderers of the deployment files share: the generated-file notice and how a line is written. */
final class Text {

    /** Every file the command renders says so with these words (a comment in the format of the file); {@link DeployFiles} knows its own files by them. */
    static final String MARKER = "Rendered by `ledger-cli render-deploy`";
    static final String GENERATED = "# " + MARKER + " (deploy/install.sh). Do not edit: the next run replaces this file.";

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
