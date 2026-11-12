package org.pgledger.cli;

import java.io.PrintStream;
import org.springframework.stereotype.Component;

/** The only place that touches the process standard streams: the output of every command goes to standard output. */
@Component
final class StreamCliOutput implements CliOutput {

    private final PrintStream info;

    StreamCliOutput() {
        this.info = System.out;
    }

    @Override
    public void info(String text) {
        info.println(text);
    }
}
