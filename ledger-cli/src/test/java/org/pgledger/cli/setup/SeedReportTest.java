package org.pgledger.cli.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.seed.SeedCode;

/** SET-024: the comparison of the seed with the codes of the database, by counts and digest. */
class SeedReportTest {

    private static SeedCode code(String code, String name, int sort, String state) {
        return new SeedCode("DEFECT_CATEGORY", code, name, name + " en", "설명 " + code, "Description " + code, sort, state);
    }

    private static List<SeedCode> seed() {
        return List.of(code("AAA", "첫째", 10, "ACTIVE"), code("BBB", "둘째", 20, "ACTIVE"), code("CCC", "셋째", 30, "ACTIVE"));
    }

    @Test
    @DisplayName("SET-024 all equal: every code present and equal, no change, equal digests; extra codes of the owner are not counted and change no digest")
    void allEqual() {
        List<SeedCode> database = new ArrayList<>(seed());
        database.add(code("OWN", "내것", 5, "ACTIVE"));

        SeedReport report = SeedReport.compare(seed(), database);

        assertThat(report).isEqualTo(new SeedReport(3, 3, 3, 0, report.seedDigest(), report.seedDigest()));
        assertThat(report.databaseDigest()).isEqualTo(report.seedDigest());
    }

    @Test
    @DisplayName("SET-024 one text changed and one code retired: present 3, equal 1, changed 2, and the digests differ")
    void changedAndRetired() {
        List<SeedCode> database = List.of(code("AAA", "바꿈", 10, "ACTIVE"), code("BBB", "둘째", 20, "INACTIVE"),
                code("CCC", "셋째", 30, "ACTIVE"));

        SeedReport report = SeedReport.compare(seed(), database);

        assertThat(report.seed()).isEqualTo(3);
        assertThat(report.present()).isEqualTo(3);
        assertThat(report.same()).isEqualTo(1);
        assertThat(report.changed()).isEqualTo(2);
        assertThat(report.databaseDigest()).isNotEqualTo(report.seedDigest());
    }

    @Test
    @DisplayName("SET-024 a code of the seed that the database lacks: present 2, equal 2, changed 0, and the digests differ")
    void missing() {
        SeedReport report = SeedReport.compare(seed(), List.of(seed().get(0), seed().get(2)));

        assertThat(report.present()).isEqualTo(2);
        assertThat(report.same()).isEqualTo(2);
        assertThat(report.changed()).isZero();
        assertThat(report.databaseDigest()).isNotEqualTo(report.seedDigest());
    }
}
