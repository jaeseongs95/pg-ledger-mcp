package org.pgledger.cli;

/** The single target for everything a command prints. */
public interface CliOutput {

    void info(String text);

    void error(String text);
}
