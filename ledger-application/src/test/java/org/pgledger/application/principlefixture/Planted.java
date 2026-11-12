package org.pgledger.application.principlefixture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Transactional;

/**
 * ARC-PRN-07: classes that break the architecture rules of the development principles on purpose, so that a test can show that
 * each rule finds them. Nothing in the product uses this package, and the rules of the modules look at production classes only.
 */
public final class Planted {

    /**
     * A class with a {@code @Bean} method of a type of ours. It is not annotated {@code @Configuration}, so that the component
     * scan of the other tests of this module never makes a bean of it.
     */
    public static class Wiring {

        @Bean
        Planted planted() {
            return new Planted();
        }
    }

    /** An Optional that is stored. */
    private final Optional<String> maybe = Optional.empty();

    /** An Optional that is passed. */
    void take(Optional<String> given) {
        throw new UnsupportedOperationException(String.valueOf(given) + maybe);
    }

    /** An Optional that is taken apart with get(). */
    String unwrap() {
        return Optional.of("x").get();
    }

    /** A transaction that writes a file itself. */
    @Transactional
    void writeDirect(Path file) throws IOException {
        Files.writeString(file, "x");
    }

    /** A transaction that writes a file through a method of ours. */
    @Transactional
    void writeIndirect(Path file) throws IOException {
        deleteIt(file);
    }

    private void deleteIt(Path file) throws IOException {
        Files.delete(file);
    }

    /**
     * A transaction that registers a write for later in a lambda. ArchUnit attributes the calls of a lambda body to the method
     * that holds it, so the rule finds this one too: a write that is meant to run after the commit says so on the allow list.
     */
    @Transactional
    Runnable writeInALambda(Path file) {
        return () -> {
            try {
                Files.writeString(file, "x");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }
}
