package org.pgledger.domain.error;

/**
 * Error tokens of category INFRASTRUCTURE: the driver, internal and integrity failures, and the failures of the
 * schema (its files, its history, the grants of the accounts, the time zone the schema work needs). The schema
 * codes are what the schema migration, the schema verification and the session clock check throw.
 */
public enum InfrastructureCode implements ErrorCode {
    DRIVER_NOT_CONFIGURED,
    DRIVER_INVALID,
    INTERNAL_ERROR,
    INTEGRITY_VIOLATION,
    /** A schema file is not named {@code V<number>__<description>.sql}; {0} = the file name. */
    SCHEMA_FILE_NAME_INVALID,
    /** Two schema files have one version; {0} = the version. */
    SCHEMA_VERSION_DUPLICATE,
    /** The versions of the schema files are not 1, 2, 3 ...; {0} = the expected version, {1} = the version found. */
    SCHEMA_VERSION_GAP,
    /** The schema files cannot be read; {0} = where they are read from. */
    SCHEMA_FILES_UNREADABLE,
    /** The database cannot be reached for the schema work. */
    SCHEMA_DATABASE_UNREACHABLE,
    /** The database has no schema history: the migrate command was never run. */
    SCHEMA_HISTORY_MISSING,
    /** The schema history table cannot be created. */
    SCHEMA_HISTORY_NOT_CREATED,
    /** A version was applied but there is no schema file for it; {0} = the version, {1} = the file name recorded. */
    SCHEMA_APPLIED_FILE_MISSING,
    /**
     * The file differs from what was applied. {0} = the version, {1} = the file name, {2} = the digest that was
     * applied, {3} = the digest of the file.
     */
    SCHEMA_DIGEST_MISMATCH,
    /** The latest recorded outcome of a version is FAILED; {0} = the version, {1} = the file name. */
    SCHEMA_RECORDED_FAILED,
    /** A version is not applied but a later one is; {0} = the version, {1} = the file name, {2} = the later version. */
    SCHEMA_OUT_OF_ORDER,
    /** A version is not applied yet; {0} = the version, {1} = the file name. */
    SCHEMA_PENDING,
    /** A migration failed (its cause is the database failure); {0} = the version, {1} = the file name. */
    SCHEMA_MIGRATION_FAILED,
    /** The wait for another migration process was interrupted. */
    SCHEMA_MIGRATION_INTERRUPTED,
    /** The grants cannot be applied to the account. */
    SCHEMA_GRANT_FAILED,
    /** The default time zone of the JVM is not UTC. */
    JVM_ZONE_NOT_UTC,
    /** The session time zone of the database is not UTC; {0} = how far its clock is from the UTC clock. */
    SESSION_ZONE_NOT_UTC,
    /** The session clock of the database cannot be read. */
    SESSION_CLOCK_UNREADABLE;

    @Override
    public ErrorCategory category() {
        return ErrorCategory.INFRASTRUCTURE;
    }
}
