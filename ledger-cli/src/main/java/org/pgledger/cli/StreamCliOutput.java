package org.pgledger.cli;

import java.io.PrintStream;

final class StreamCliOutput implements CliOutput {

    private final PrintStream info;

    StreamCliOutput(PrintStream info) {
        this.info = info;
    }

    @Override
    public void info(String text) {
        info.println(text);
    }
}
