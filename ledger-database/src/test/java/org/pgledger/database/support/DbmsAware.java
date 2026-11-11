package org.pgledger.database.support;

/** A test class that runs once per DBMS (a {@code @ParameterizedClass}) and tells the extension which one. */
public interface DbmsAware {

    Dbms dbms();
}
