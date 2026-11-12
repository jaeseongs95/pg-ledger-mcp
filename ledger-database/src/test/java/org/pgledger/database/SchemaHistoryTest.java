package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.database.repository.SchemaHistoryRepository.Outcome;
import org.pgledger.database.repository.SchemaHistoryRepository.Row;
import org.pgledger.database.schema.MigrationFile;
import org.pgledger.database.schema.SchemaHistory;
import org.pgledger.database.schema.SchemaHistory.Verdict;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.value.Sha256;

/**
 * SCH-030 (SchemaHistory): the one judgement of a schema file against {@code schema_history} that the migrator and the
 * verifier share. No database: the history is a value, so every state is a list of rows.
 */
class SchemaHistoryTest {

    private static final Instant T0 = Instant.parse("2026-11-05T10:00:00Z");
    private static final MigrationFile TWO = file(2, "two");
    private static final MigrationFile EDITED_TWO = file(2, "two -- edited");

    private static MigrationFile file(int version, String text) {
        return new MigrationFile(version, "V" + version + "__x.sql", text, Sha256.ofText(text));
    }

    private static Row row(MigrationFile file, int second, Outcome outcome) {
        return new Row(file.version(), file.fileName(), file.digest(), T0.plusSeconds(second), outcome);
    }

    /** A history, the file it is asked about, and what it must say. */
    private record Case(String name, List<Row> rows, MigrationFile file, Verdict verdict) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Case> cases() {
        return Stream.of(
                new Case("never attempted", List.of(row(file(1, "one"), 0, Outcome.SUCCESS)), TWO, Verdict.PENDING),
                new Case("only failed attempts", List.of(row(TWO, 0, Outcome.FAILED), row(TWO, 1, Outcome.FAILED)), TWO,
                        Verdict.FAILED_ONLY),
                new Case("applied and intact", List.of(row(TWO, 0, Outcome.SUCCESS)), TWO, Verdict.INTACT),
                new Case("failed once, then applied", List.of(row(TWO, 0, Outcome.FAILED), row(TWO, 1, Outcome.SUCCESS)),
                        TWO, Verdict.INTACT),
                new Case("the applied file was edited", List.of(row(TWO, 0, Outcome.SUCCESS)), EDITED_TWO,
                        Verdict.DIGEST_CHANGED),
                new Case("edited, and the edit was recorded as failed",
                        List.of(row(TWO, 0, Outcome.SUCCESS), row(EDITED_TWO, 1, Outcome.FAILED)), EDITED_TWO,
                        Verdict.DIGEST_CHANGED),
                new Case("applied, then the latest attempt failed",
                        List.of(row(TWO, 0, Outcome.SUCCESS), row(TWO, 1, Outcome.FAILED)), TWO, Verdict.LATEST_FAILED));
    }

    @ParameterizedTest
    @MethodSource("cases")
    @DisplayName("SCH-030 the verdict of a file follows from the attempts of its version")
    void theVerdict(Case c) {
        assertThat(new SchemaHistory(c.rows()).verdictOf(c.file())).isEqualTo(c.verdict());
    }

    @Test
    @DisplayName("SCH-030 only the attempts of the version of the file count")
    void otherVersionsAreNotLookedAt() {
        SchemaHistory history = new SchemaHistory(List.of(row(file(1, "one"), 0, Outcome.FAILED),
                row(TWO, 1, Outcome.SUCCESS)));
        assertThat(history.verdictOf(TWO)).isEqualTo(Verdict.INTACT);
        assertThat(history.verdictOf(file(1, "one"))).isEqualTo(Verdict.FAILED_ONLY);
        assertThat(history.attemptsOf(2)).hasSize(1);
    }

    @Test
    @DisplayName("SCH-030 a version of the history without a file is SCHEMA_APPLIED_FILE_MISSING; a later success with the version not applied is SCHEMA_OUT_OF_ORDER")
    void theRefusalsThatDoNotDependOnOneFile() {
        SchemaHistory history = new SchemaHistory(List.of(row(file(1, "one"), 0, Outcome.SUCCESS),
                row(file(3, "three"), 1, Outcome.SUCCESS)));

        InfrastructureException missing = catchThrowableOfType(InfrastructureException.class,
                () -> history.requireFileForEveryVersion(List.of(file(1, "one"))));
        assertThat(missing.code()).isEqualTo(InfrastructureCode.SCHEMA_APPLIED_FILE_MISSING);
        assertThat(missing.args()).containsExactly("3", "V3__x.sql");
        history.requireFileForEveryVersion(List.of(file(1, "one"), file(3, "three")));

        InfrastructureException outOfOrder = catchThrowableOfType(InfrastructureException.class,
                () -> history.requireNothingLaterApplied(TWO));
        assertThat(outOfOrder.code()).isEqualTo(InfrastructureCode.SCHEMA_OUT_OF_ORDER);
        assertThat(outOfOrder.args()).containsExactly("2", "V2__x.sql", "3");
        history.requireNothingLaterApplied(file(4, "four"));
    }

    @Test
    @DisplayName("SCH-030 a digest mismatch names both digests; a failure is only recorded again when it is not recorded yet")
    void theMismatchAndTheRecordedFailure() {
        SchemaHistory applied = new SchemaHistory(List.of(row(TWO, 0, Outcome.SUCCESS)));
        assertThat(applied.digestMismatch(EDITED_TWO).args()).containsExactly("2", "V2__x.sql", TWO.digest().hex(),
                EDITED_TWO.digest().hex());
        assertThat(applied.latestFailureIsOf(EDITED_TWO)).isFalse();

        SchemaHistory recorded = new SchemaHistory(List.of(row(TWO, 0, Outcome.SUCCESS),
                row(EDITED_TWO, 1, Outcome.FAILED)));
        assertThat(recorded.latestFailureIsOf(EDITED_TWO)).isTrue();
        assertThat(recorded.latestFailureIsOf(TWO)).as("a failure of another digest").isFalse();
        assertThat(new SchemaHistory(List.of()).latestFailureIsOf(TWO)).isFalse();
    }
}
