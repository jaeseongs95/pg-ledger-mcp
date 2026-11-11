package org.pgledger.archfixture;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.TimeZone;

/** Deliberately bad code: the architecture rules must flag every method (negative control). */
public final class BadCode {

    public static int counter;

    private BadCode() {
    }

    Instant ambientNow() {
        return Instant.now();
    }

    LocalDateTime ambientLocal() {
        return LocalDateTime.now();
    }

    LocalDateTime ambientOffset() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    Instant goodNow(Clock clock) {
        return Instant.now(clock);
    }

    ZoneId defaultZone() {
        return ZoneId.systemDefault();
    }

    TimeZone defaultTimeZone() {
        return TimeZone.getDefault();
    }

    Date legacyDate() {
        return new Date();
    }

    long millis() {
        return System.currentTimeMillis();
    }

    void shout() {
        System.out.println("x");
    }

    void trace(Exception e) {
        e.printStackTrace();
    }

    Class<?> load() throws ClassNotFoundException {
        return Class.forName("java.lang.String");
    }

    void open(Field f) {
        f.setAccessible(true);
    }

    String env() {
        return System.getenv("HOME");
    }

    ThreadLocal<String> local() {
        return new ThreadLocal<>();
    }

    void run() throws java.io.IOException {
        new ProcessBuilder("ls").start();
    }
}
