package org.pgledger.cli.setup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.pgledger.domain.model.CommonCode;
import org.pgledger.seed.SeedCode;
import org.pgledger.seed.SeedDigest;

/**
 * The comparison of the seed with the codes of the database, by counts and by digest. Only the codes the seed names are
 * compared: a code the owner added is not counted and does not change a digest. A code of the seed that the owner changed
 * or retired is present but not equal (it is kept as it is); the digest of the database is the digest of the codes the seed
 * names as the database has them, so it equals the digest of the seed exactly when every one of them is as the seed has it.
 *
 * @param seed           the number of codes in the seed
 * @param present        how many of them the database has
 * @param same           how many of those are equal to the seed (every field, the sort order and the state included)
 * @param changed        how many of those differ from the seed
 * @param seedDigest     the digest of the codes of the seed
 * @param databaseDigest the digest of the same codes as the database has them
 */
record SeedReport(int seed, int present, int same, int changed, String seedDigest, String databaseDigest) {

    /** @param database the codes of the database (any codes of the groups of the seed) */
    static SeedReport compare(List<SeedCode> seed, List<SeedCode> database) {
        Map<String, SeedCode> byKey = new HashMap<>();
        for (SeedCode code : database) {
            byKey.put(key(code), code);
        }
        List<SeedCode> found = new ArrayList<>();
        int same = 0;
        for (SeedCode wanted : seed) {
            SeedCode have = byKey.get(key(wanted));
            if (have != null) {
                found.add(have);
                if (Objects.equals(have, wanted)) {
                    same++;
                }
            }
        }
        return new SeedReport(seed.size(), found.size(), same, found.size() - same, SeedDigest.of(seed),
                SeedDigest.of(found));
    }

    /** A code of the ledger in the form of the seed. */
    static SeedCode of(CommonCode code) {
        return new SeedCode(code.group().name(), code.code().value(), code.nameKo(), code.nameEn(), code.descriptionKo(),
                code.descriptionEn(), code.sortOrder(), code.state().name());
    }

    private static String key(SeedCode code) {
        return code.group() + "/" + code.code();
    }
}
