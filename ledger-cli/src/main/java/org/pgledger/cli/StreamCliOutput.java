package org.pgledger.cli;

import java.io.PrintStream;

final class StreamCliOutput implements CliOutput {

    private final PrintStream info;
    private final PrintStream error;

    StreamCliOutput(PrintStream info, PrintStream error) {
        this.info = info;
        this.error = error;
    }

    @Override
    public void info(String text) {
        info.println(text);
    }

    @Override
    public void error(String text) {
        error.println(text);
    }
}
