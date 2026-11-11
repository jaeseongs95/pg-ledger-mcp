package org.pgledger.database.schema;

/** The schema files and the schema history disagree, a file failed, or the database is not ready; stops startup. */
public class SchemaMigrationException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public SchemaMigrationException(String message) {
        super(message);
    }

    public SchemaMigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
