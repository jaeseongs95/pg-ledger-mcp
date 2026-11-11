package org.pgledger.database.fake;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/** A driver that connects to nothing and accepts URLs that start with {@code jdbc:fake:}. */
public abstract class BaseFakeDriver implements Driver {

    @Override
    public Connection connect(String url, Properties info) {
        return null;
    }

    @Override
    public boolean acceptsURL(String url) {
        return url.startsWith("jdbc:fake:");
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }
}
