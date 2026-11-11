package org.pgledger.cli;

/** The single target for what a successful command prints; refusals are not printed here, Boot reports them ({@link CliFailureAnalyzer}). */
public interface CliOutput {

    void info(String text);
}
