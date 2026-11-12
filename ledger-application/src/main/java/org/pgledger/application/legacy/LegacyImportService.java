package org.pgledger.application.legacy;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.pgledger.application.admin.OwnerAuditWriter;
import org.pgledger.database.repository.CodeRevisionRepository;
import org.pgledger.database.repository.ColumnRule;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.LegacyRow;
import org.pgledger.database.repository.LegacySourceRepository;
import org.pgledger.database.repository.LegacyTable;
import org.pgledger.database.repository.LegacyTargetRepository;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.LookupException;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationIssue;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.rule.OwnerAction;
import org.pgledger.domain.value.OwnerIdentity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Copies the legacy ledger (the old schema {@code ledger}, read only) into the new schema, and checks the copy. The whole
 * import is one transaction of the new schema (the class is {@code @Transactional}), so a failure anywhere leaves no
 * imported row and a rerun can succeed; the legacy side is read in one read-only snapshot
 * ({@link LegacySourceRepository#readSnapshot}) that the import never writes to.
 *
 * <p>Steps, in this order:
 * <ol>
 * <li>The new schema must hold no ledger data yet ({@link LegacyTargetRepository#tablesWithData}); otherwise
 * {@link ConflictCode#DUPLICATE_KEY}, nothing is written (MIG-009).</li>
 * <li>The legacy schema must be complete (its installer steps, MIG-021); otherwise {@link LookupCode#NOT_FOUND}.</li>
 * <li>Validation pass: every legacy row is mapped ({@link LegacyRowMapper}); every row that cannot be imported as it is
 * (an empty text in a NOT NULL column, an id that is not an upper-case id, a text longer than its column ...) is
 * collected, and when there is any the import stops with one {@link ValidationException} that lists them all
 * (MIG-023, MIG-028). Nothing is written, no value is invented. The numbers the legacy ledger used are collected in the
 * same pass.</li>
 * <li>Copy pass: the tables are written in the order of {@link LegacyTable} (defects with the merge target first), the
 * rows through the repository of the new schema; the counters of the new schema continue after the highest legacy ids
 * (MIG-007); the code revision is bumped so a running server reloads the common codes; the grants are copied without a
 * session key (the owner registers the key of each session, then the grant works).</li>
 * <li>Comparison: per table the number of rows and the SHA-256 digest of the canonical rows of the legacy side (as mapped)
 * and of the new schema (read back) must be equal (MIG-002, MIG-019); otherwise {@link InfrastructureCode#INTEGRITY_VIOLATION}
 * with the table, both counts and both digests, and the transaction rolls back.</li>
 * <li>One audit row {@link OwnerAction#IMPORT} names the actor.</li>
 * </ol>
 *
 * <p>The import is an operator command, so the service exists only where {@code ledger.cli.enabled=true} (the command line
 * tool sets it in its own {@code application.properties}); the MCP server and the admin web have no such bean.
 *
 * <p>{@link #verify()} runs the validation and the comparison alone, against what the new schema holds now.
 */
@Service
@ConditionalOnProperty(prefix = "ledger.cli", name = "enabled", havingValue = "true")
@Transactional
public class LegacyImportService {

    /** The surrogate key sequences whose counters continue after the highest legacy id. */
    private static final List<Counter> COPIED_COUNTERS = List.of(Counter.HISTORY, Counter.DEFECT_FIX,
            Counter.DEFECT_CASE, Counter.SESSION_GRANT);

    private static final String VALIDATION_SUBJECT = "legacy data";
    private static final String MISSING_STEP = "legacy migration step";

    private final ObjectProvider<LegacySourceRepository> sources;
    private final LegacyTargetRepository target;
    private final IdAllocator ids;
    private final CodeRevisionRepository revision;
    private final OwnerAuditWriter audit;
    private final LegacyRowMapper mapper;

    public LegacyImportService(ObjectProvider<LegacySourceRepository> sources, LegacyTargetRepository target,
            IdAllocator ids, CodeRevisionRepository revision, OwnerAuditWriter audit, LegacyRowMapper mapper) {
        this.sources = sources;
        this.target = target;
        this.ids = ids;
        this.revision = revision;
        this.audit = audit;
        this.mapper = mapper;
    }

    /** True when the connection to the legacy ledger is configured ({@code ledger.import.source.url}). */
    @Transactional(propagation = Propagation.SUPPORTS)
    public boolean sourceConfigured() {
        return sources.getIfAvailable() != null;
    }

    /**
     * Imports the legacy ledger and checks the copy.
     *
     * @param actor who runs the import (the audit row names it)
     * @throws ConflictException DUPLICATE_KEY: the new schema already holds ledger data (the tables are the argument)
     * @throws LookupException NOT_FOUND: the legacy schema lacks installer steps (they are the argument)
     * @throws ValidationException VALIDATION_FAILED: rows that cannot be imported as they are (they are the issues)
     * @throws InfrastructureException INTEGRITY_VIOLATION: a table of the new schema differs from the legacy table (the
     *         arguments are groups of five: table, legacy rows, new rows, legacy digest, new digest)
     */
    public ImportReport importLegacy(OwnerIdentity actor) {
        Objects.requireNonNull(actor, "actor");
        LegacySourceRepository source = requireSource();
        List<String> occupied = target.tablesWithData();
        if (!occupied.isEmpty()) {
            throw new ConflictException(ConflictCode.DUPLICATE_KEY,
                    List.of("ledger data in " + String.join(", ", occupied)));
        }
        return source.readSnapshot(() -> {
            requireSteps(source);
            Map<LegacyTable, Map<String, ColumnRule>> rules = columnRules();
            ImportCounters counters = new ImportCounters();
            refuse(validate(source, rules, counters));
            Map<LegacyTable, TableDigest> legacySide = pass(source, rules, counters, true);
            for (Counter counter : COPIED_COUNTERS) {
                ids.raise(counter, counters.highest(counter));
            }
            revision.bump();
            ImportReport report = compare(source.schema(), legacySide);
            audit.record(actor, OwnerAction.IMPORT, "legacy schema " + source.schema(), report.detail());
            return report;
        });
    }

    /**
     * Compares the legacy ledger with what the new schema holds now, table by table, as the import does after the copy:
     * the same validation and the same count and digest per table; nothing is written.
     *
     * @throws InfrastructureException INTEGRITY_VIOLATION as for {@link #importLegacy}
     */
    @Transactional(readOnly = true)
    public ImportReport verify() {
        LegacySourceRepository source = requireSource();
        return source.readSnapshot(() -> {
            requireSteps(source);
            Map<LegacyTable, Map<String, ColumnRule>> rules = columnRules();
            ImportCounters counters = new ImportCounters();
            refuse(validate(source, rules, counters));
            return compare(source.schema(), pass(source, rules, counters, false));
        });
    }

    private LegacySourceRepository requireSource() {
        LegacySourceRepository source = sources.getIfAvailable();
        if (source == null) {
            throw new IllegalStateException("the legacy source is not configured (ledger.import.source.url); "
                    + "the caller asks sourceConfigured() first");
        }
        return source;
    }

    private static void requireSteps(LegacySourceRepository source) {
        List<String> missing = source.missingSteps();
        if (!missing.isEmpty()) {
            throw new LookupException(LookupCode.NOT_FOUND, List.of(MISSING_STEP, String.join(", ", missing)));
        }
    }

    private Map<LegacyTable, Map<String, ColumnRule>> columnRules() {
        Map<LegacyTable, Map<String, ColumnRule>> rules = new EnumMap<>(LegacyTable.class);
        for (LegacyTable table : LegacyTable.values()) {
            rules.put(table, target.columnRules(table));
        }
        return rules;
    }

    /** The validation pass: every row of every table is mapped, nothing is written; the numbers in use are collected. */
    private List<LegacyViolation> validate(LegacySourceRepository source,
            Map<LegacyTable, Map<String, ColumnRule>> rules, ImportCounters counters) {
        List<LegacyViolation> violations = new ArrayList<>();
        for (LegacyTable table : LegacyTable.values()) {
            List<LegacyRow> defects = new ArrayList<>();
            source.scan(table, row -> {
                counters.collect(table, row);
                LegacyRowMapper.Mapped mapped = mapper.map(table, row, rules.get(table), counters);
                violations.addAll(mapped.violations());
                if (table == LegacyTable.DEFECT) {
                    defects.add(mapped.row());
                }
            });
            if (table == LegacyTable.DEFECT) {
                for (String id : DefectOrder.of(defects).loops()) {
                    violations.add(new LegacyViolation(table.targetName(), id, "merged_into",
                            ValidationReason.MERGE_CHAIN_LOOPS, List.of()));
                }
            }
        }
        return violations;
    }

    private static void refuse(List<LegacyViolation> violations) {
        if (!violations.isEmpty()) {
            List<ValidationIssue> issues = violations.stream().map(LegacyViolation::toIssue).toList();
            throw new ValidationException(ValidationCode.VALIDATION_FAILED, List.of(VALIDATION_SUBJECT), issues,
                    Map.of());
        }
    }

    /**
     * The pass over the legacy tables that makes the legacy side of the comparison: every row is mapped to the row of the
     * new schema, added to the digest of its table and, when {@code write} is set, written.
     */
    private Map<LegacyTable, TableDigest> pass(LegacySourceRepository source,
            Map<LegacyTable, Map<String, ColumnRule>> rules, ImportCounters counters, boolean write) {
        Map<LegacyTable, TableDigest> digests = new EnumMap<>(LegacyTable.class);
        for (LegacyTable table : LegacyTable.values()) {
            TableDigest digest = new TableDigest(table);
            digests.put(table, digest);
            Map<String, ColumnRule> tableRules = rules.get(table);
            if (table == LegacyTable.DEFECT) {
                List<LegacyRow> defects = new ArrayList<>();
                source.scan(table, row -> defects.add(mapper.map(table, row, tableRules, counters).row()));
                for (LegacyRow row : DefectOrder.of(defects).ordered()) {
                    digest.add(row);
                    if (write) {
                        target.insert(table, row);
                    }
                }
            } else {
                source.scan(table, row -> {
                    LegacyRow mapped = mapper.map(table, row, tableRules, counters).row();
                    digest.add(mapped);
                    if (write) {
                        store(table, mapped);
                    }
                });
            }
        }
        return digests;
    }

    private void store(LegacyTable table, LegacyRow row) {
        if (table == LegacyTable.COMMON_CODE) {
            target.putCommonCode(row);
        } else {
            target.insert(table, row);
        }
    }

    /** Reads every table of the new schema back and compares it with the legacy side. */
    private ImportReport compare(String schema, Map<LegacyTable, TableDigest> legacySide) {
        List<TableCheck> checks = new ArrayList<>();
        for (LegacyTable table : LegacyTable.values()) {
            TableDigest legacy = legacySide.get(table);
            TableDigest current = new TableDigest(table);
            if (table.targetMayHoldOtherRows()) {
                target.scan(table, row -> current.addIfKeyed(row, legacy.keys()));
            } else {
                target.scan(table, current::add);
            }
            checks.add(new TableCheck(table, legacy.count(), current.count(), legacy.digest(), current.digest()));
        }
        List<String> differences = new ArrayList<>();
        for (TableCheck check : checks) {
            if (!check.matches()) {
                differences.addAll(List.of(check.table().targetName(), String.valueOf(check.sourceRows()),
                        String.valueOf(check.targetRows()), check.sourceDigest().hex(), check.targetDigest().hex()));
            }
        }
        if (!differences.isEmpty()) {
            throw new InfrastructureException(InfrastructureCode.INTEGRITY_VIOLATION, differences);
        }
        return new ImportReport(schema, checks);
    }
}
