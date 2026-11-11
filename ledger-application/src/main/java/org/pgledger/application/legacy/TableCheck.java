package org.pgledger.application.legacy;

import org.pgledger.database.repository.LegacyTable;
import org.pgledger.domain.value.Sha256;

/**
 * The comparison of one table: how many rows and which digest the legacy table has (after the documented normalisation)
 * and how many rows and which digest the new table has. The digest is the SHA-256 over the lines
 * {@code <key> TAB <SHA-256 of the canonical row> LF} in key order (see {@code CanonicalRow}).
 *
 * @param table        the table
 * @param sourceRows   the rows read from the legacy table
 * @param targetRows   the rows read from the new table (for the common codes only those of the legacy keys)
 * @param sourceDigest the digest of the legacy side
 * @param targetDigest the digest of the new side
 */
public record TableCheck(LegacyTable table, long sourceRows, long targetRows, Sha256 sourceDigest,
        Sha256 targetDigest) {

    public boolean matches() {
        return sourceRows == targetRows && sourceDigest.equals(targetDigest);
    }
}
