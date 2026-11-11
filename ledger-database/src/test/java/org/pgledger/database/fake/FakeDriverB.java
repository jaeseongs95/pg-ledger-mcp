package org.pgledger.database.fake;

/** Second driver of the jar: must never be loaded when only {@link FakeDriverA} is registered. */
public final class FakeDriverB extends BaseFakeDriver {

    public static final String MARK = "pgledger.test.loaded.B";

    static {
        System.setProperty(MARK, "yes");
    }

    public FakeDriverB() {
    }
}
