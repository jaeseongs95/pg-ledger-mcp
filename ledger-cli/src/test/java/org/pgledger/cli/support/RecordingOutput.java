package org.pgledger.cli.support;

import java.util.ArrayList;
import java.util.List;

import org.pgledger.cli.CliOutput;

/** Test double for the single console output target. */
public final class RecordingOutput implements CliOutput {

    private final List<String> info = new ArrayList<>();
    private final List<String> error = new ArrayList<>();

    @Override
    public void info(String text) {
        info.add(text);
    }

    @Override
    public void error(String text) {
        error.add(text);
    }

    public String info() {
        return String.join("\n", info);
    }

    public String error() {
        return String.join("\n", error);
    }
}
