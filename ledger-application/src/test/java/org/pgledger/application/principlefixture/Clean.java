package org.pgledger.application.principlefixture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Transactional;

/** ARC-PRN-07: the clean counterparts of {@link Planted}; no rule finds anything here. */
public final class Clean {

    /** A {@code @Bean} method of a type that is not ours (not a {@code @Configuration}: see {@link Planted.Wiring}). */
    public static class Wiring {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    /** An Optional is the answer of a method. */
    Optional<String> answer() {
        return Optional.of("x");
    }

    /** An Optional is mapped, never taken apart. */
    String mapped() {
        return answer().map(String::strip).orElse("");
    }

    /** A transaction that reads a file and writes none. */
    @Transactional
    String readsOnly(Path file) throws IOException {
        return Files.readString(file);
    }
}
