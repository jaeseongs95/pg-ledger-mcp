package org.pgledger.testing;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The contract vectors that the hook and the server share: JSON files under {@code contract/} of this test fixture.
 * The hook (a Spring free jar that depends on the attestation module only) and the server are two programs that each
 * define the author format and the tool names; both test against the same files, so the two definitions cannot drift
 * apart unnoticed. The files are JSON so that the vectors can hold a line feed, a non ASCII character or a null.
 */
public final class ContractVectors {

    /** Authors: {@code valid} is a list of texts, {@code invalid} a list of {@code {value, why}}; a null value is allowed. */
    public static final String AUTHORS = "/contract/author-vectors.json";

    /** Tool names: {@code write} (the ten that carry the attestation) and {@code read}, canonical {@code ledger_<op>}. */
    public static final String WRITE_TOOLS = "/contract/write-tool-vectors.json";

    private ContractVectors() {
    }

    /** The bytes (UTF-8 JSON) of one of the vector files named by the constants of this class. */
    public static byte[] read(String resource) {
        try (InputStream stream = ContractVectors.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("the contract vector file is missing: " + resource);
            }
            return stream.readAllBytes();
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot read the contract vector file " + resource, failure);
        }
    }
}
