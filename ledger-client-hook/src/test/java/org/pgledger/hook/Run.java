package org.pgledger.hook;

import java.nio.charset.StandardCharsets;

/** The result of one hook invocation: exit code and the bytes on stdout and stderr. */
record Run(int exit, byte[] stdout, byte[] stderr) {

    String out() {
        return new String(stdout, StandardCharsets.UTF_8);
    }

    String err() {
        return new String(stderr, StandardCharsets.UTF_8);
    }
}
