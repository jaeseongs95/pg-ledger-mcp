package org.pgledger.database.fake;

/** First of two drivers in one jar; its class initialisation leaves a mark in the JVM (proves it was loaded). */
public final class FakeDriverA extends BaseFakeDriver {

    public static final String MARK = "pgledger.test.loaded.A";

    static {
        System.setProperty(MARK, "yes");
    }

    public FakeDriverA() {
    }
}
