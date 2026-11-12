package org.pgledger.cli.secret;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.InputStream;

import org.springframework.stereotype.Component;

/**
 * The standard input of the process, and the only class that touches it (an architecture rule says so). It is read without a
 * buffer of its own: {@code System.in} would read ahead and take lines that belong to the next command of an installation
 * that gives several secrets on one input, so the stream is a {@link FileInputStream} of {@link FileDescriptor#in} and a
 * read takes the bytes asked for and no more. Nothing reads it unless the command line has {@code --stdin}. The constructor
 * with a stream is for a test, which plays the input.
 */
@Component
public class StandardInput {

    private final InputStream stream;

    public StandardInput() {
        this.stream = ofProcess();
    }

    public StandardInput(InputStream stream) {
        this.stream = stream;
    }

    /** The input. It is never closed by the program. */
    public InputStream stream() {
        return stream;
    }

    /** The standard input of the process, also for the code that runs before the application context exists. */
    public static InputStream ofProcess() {
        return new FileInputStream(FileDescriptor.in);
    }
}
