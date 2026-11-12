package org.pgledger.seed;

/**
 * One common code of the seed, in the form it has in the ledger ({@code common_code}): the key is the group and the code,
 * the state is the name of the state ({@code ACTIVE} for every code of the seed).
 */
public record SeedCode(String group, String code, String nameKo, String nameEn, String descriptionKo, String descriptionEn,
        int sortOrder, String state) {
}
