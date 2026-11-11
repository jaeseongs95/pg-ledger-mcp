package org.pgledger.admin.app;

/** What the application can do: the whole console, or (no usable JDBC driver) only login and driver registration. */
public enum AdminMode {
    CONSOLE, SETUP
}
