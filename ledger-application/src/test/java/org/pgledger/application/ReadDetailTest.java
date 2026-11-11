package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.pgledger.application.read.AttentionService;
import org.pgledger.application.read.AttentionService.AttentionQuery;
import org.pgledger.application.read.DetailService;
import org.pgledger.application.read.DetailService.CaseView;
import org.pgledger.application.read.DetailService.DefectDetail;
import org.pgledger.application.read.GetService;
import org.pgledger.application.support.Dbms;
import org.pgledger.application.support.DbmsAware;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.H2ServiceTest;
import org.pgledger.application.support.MergeFixture;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.BatchOpInput;
import org.pgledger.application.write.BatchService;
import org.pgledger.application.write.LinkCommand;
import org.pgledger.application.write.LinkService;
import org.pgledger.application.write.RecordCaseCommand;
import org.pgledger.application.write.RecordCaseService;
import org.pgledger.application.write.ReviewService;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.TransitionService;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.rule.ActorAssurance;
import org.pgledger.domain.rule.AttentionKind;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.value.JsonDocument;
import org.pgledger.domain.value.LegacyActorId;
import org.pgledger.domain.value.RequestId;

/** MCR-G01 .. MCR-G07, MCR-D01 .. MCR-D05, MCR-T01, MCR-T02 and the read side of SVC-CAS-12 .. -14, SVC-FIX-11, -12. */
@H2ServiceTest
class ReadDetailTest implements DbmsAware {

    @Parameter
    Dbms dbms;

    @Override
    public Dbms dbms() {
        return dbms;
    }

    // ------------------------------------------------------------------- get

    @Test
    @DisplayName("MCR-G01: a defect, a task and a condition in one call, in the order of the request")
    void g01_mixedIds(Scenario s) {
        SaveResult d = s.newDefect("d");
        SaveResult t = s.newTask("t");
        SaveResult c = s.newCondition("1", "r", "c");
        var result = s.bean(GetService.class).get(List.of(c.id(), d.id(), t.id()));
        assertThat(result.items()).extracting(GetService.Item::entity).containsExactly("condition", "defect", "task");
        assertThat(result.items()).extracting(GetService.Item::state).containsExactly("UNKNOWN", "OPEN", "PLANNED");
        assertThat(result.items()).extracting(GetService.Item::version).containsExactly(1L, 1L, 1L);
        assertThat(result.items()).extracting(GetService.Item::projectId).containsOnly(s.projectId());
        assertThat(result.notFound()).isEmpty();
    }

    @Test
    @DisplayName("MCR-G02: unknown ids are listed, not an error; duplicates come once")
    void g02_unknownAndDuplicates(Scenario s) {
        SaveResult d = s.newDefect("d");
        var result = s.bean(GetService.class).get(List.of(d.id(), s.defectId(99), d.id(), "lower-case-1"));
        assertThat(result.items()).extracting(GetService.Item::id).containsExactly(d.id());
        assertThat(result.notFound()).containsExactly(s.defectId(99), "lower-case-1");
        assertThat(s.bean(GetService.class).get(List.of(s.defectId(98))).items()).isEmpty();
    }

    @Test
    @DisplayName("MCR-G03: the version read by get is the expected version of the next save")
    void g03_readThenWrite(Scenario s) {
        SaveResult d = s.newDefect("d");
        s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("defect", d.id(), 1, Map.of("title", "two")));
        long version = s.bean(GetService.class).get(List.of(d.id())).items().getFirst().version();
        assertThat(s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("defect", d.id(), version,
                Map.of("title", "three"))).version()).isEqualTo(3);
    }

    @Test
    @DisplayName("MCR-G04: an id that is a defect and a task gives both items")
    void g04_sameIdTwoKinds(Scenario s) {
        SaveResult d = s.newDefect("d");
        s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", d.id(), s.taskFields("t")));
        var items = s.bean(GetService.class).get(List.of(d.id())).items();
        assertThat(items).extracting(GetService.Item::entity).containsExactly("defect", "task");
    }

    @Test
    @DisplayName("MCR-G05: no ids and too many ids are refused")
    void g05_bounds(Scenario s) {
        Expect.failure(() -> s.bean(GetService.class).get(List.of()), ValidationCode.VALIDATION_FAILED);
        List<String> tooMany = IntStream.rangeClosed(1, 201).mapToObj(i -> "X-D-" + i).toList();
        Expect.failure(() -> s.bean(GetService.class).get(tooMany), ValidationCode.VALIDATION_FAILED);
        assertThat(s.bean(GetService.class).get(tooMany.subList(0, 200)).notFound()).hasSize(200);
    }

    @Test
    @DisplayName("MCR-G06: a merged defect shows where it went")
    void g06_merged(Scenario s) {
        SaveResult source = s.newDefect("source");
        SaveResult target = s.newDefect("target");
        MergeFixture.merge(s, source.id(), 1, target.id());
        var item = s.bean(GetService.class).get(List.of(source.id())).items().getFirst();
        assertThat(item.state()).isEqualTo("MERGED");
        assertThat(item.mergedInto()).isEqualTo(target.id());
    }

    @Test
    @DisplayName("MCR-G07: a verified defect reads as VERIFIED")
    void g07_verified(Scenario s) {
        SaveResult d = s.verifiedDefect("d");
        var item = s.bean(GetService.class).get(List.of(d.id())).items().getFirst();
        assertThat(item.state()).isEqualTo("VERIFIED");
        assertThat(item.version()).isEqualTo(3);
        assertThat(item.projectId()).isEqualTo(s.projectId());
    }

    // ------------------------------------------------------------ defect detail

    @Test
    @DisplayName("MCR-D01: the detail of a defect has fixes, tasks, conditions, cases, relations and changes")
    void d01_fullDetail(Scenario s) {
        SaveResult d = s.newDefect("d");
        SaveResult other = s.newDefect("other");
        SaveResult t = s.newTask("t");
        SaveResult c = s.newCondition("1", "r", "c");
        s.bean(LinkService.class).link(Scenario.AUTHOR, new LinkCommand(null, "defect_task", d.id(), t.id(),
                Scenario.REASON, null));
        s.bean(LinkService.class).link(Scenario.AUTHOR, new LinkCommand(null, "condition_defect", c.id(), d.id(),
                Scenario.REASON, null));
        s.bean(LinkService.class).link(Scenario.AUTHOR, new LinkCommand(null, "blocks", d.id(), other.id(),
                Scenario.REASON, null));
        s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR, new RecordCaseCommand(null, d.id(), "seen",
                Scenario.REASON, "log", "quote", "2026-11-05T09:00:00Z"));
        s.fix(Scenario.AUTHOR, d.id(), Scenario.commit('a'));
        DefectDetail detail = s.bean(DetailService.class).defect(d.id());
        assertThat(detail.projectId()).isEqualTo(s.projectId());
        assertThat(detail.title()).isEqualTo("d");
        assertThat(detail.status()).isEqualTo("SOURCE_FIXED");
        assertThat(detail.fixes()).extracting(DetailService.FixView::commit).containsExactly(Scenario.commit('a'));
        assertThat(detail.tasks()).extracting(DetailService.TaskRef::task).containsExactly(t.id());
        assertThat(detail.conditions()).containsExactly(c.id());
        assertThat(detail.relations()).extracting(DetailService.RelationView::kind).containsExactly("blocks");
        assertThat(detail.relations()).extracting(DetailService.RelationView::other).containsExactly(other.id());
        assertThat(detail.cases()).extracting(CaseView::observation).containsExactly("seen");
        assertThat(detail.lastChange().op()).isEqualTo("TRANSITION");
        assertThat(detail.lastChange().login()).isEqualTo("ledger_author");
        assertThat(detail.recentChanges()).hasSizeLessThanOrEqualTo(DetailService.RECENT_CHANGES).isNotEmpty();
        assertThat(detail.version()).isEqualTo(2);
    }

    @Test
    @DisplayName("MCR-D02 / SVC-CAS-12: the cases of a defect include those of the defects merged into it")
    void d02_mergedCases(Scenario s) {
        SaveResult d3 = s.newDefect("three");
        SaveResult d4 = s.newDefect("four");
        s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR, new RecordCaseCommand(null, d3.id(), "on three",
                Scenario.REASON, null, null, "2026-11-05T08:00:00Z"));
        s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR, new RecordCaseCommand(null, d4.id(), "on four",
                Scenario.REASON, null, null, "2026-11-05T09:00:00Z"));
        MergeFixture.merge(s, d4.id(), 1, d3.id());
        List<CaseView> cases = s.bean(DetailService.class).defect(d3.id()).cases();
        assertThat(cases).extracting(CaseView::source).containsExactly("case", "case", "merged_behavior");
        assertThat(cases).extracting(CaseView::observation).containsExactly("on three", "on four", "behavior of four");
        assertThat(cases).extracting(CaseView::defect).containsExactly(d3.id(), d4.id(), d4.id());
    }

    @Test
    @DisplayName("MCR-D02 / SVC-CAS-13: a merge chain of 50 defects terminates and lists every one")
    void d02_chainOfFifty(Scenario s) {
        List<BatchOpInput> ops = IntStream.rangeClosed(1, 50).mapToObj(i -> (BatchOpInput) new BatchOpInput.Save(
                "defect", s.defectFields("chain " + i), Scenario.REASON, null, null, null)).toList();
        s.bean(BatchService.class).batch(Scenario.AUTHOR, null, ops);
        IntStream.iterate(50, k -> k >= 2, k -> k - 1).forEach(k -> MergeFixture.merge(s, s.defectId(k), 1,
                s.defectId(k - 1)));
        List<CaseView> cases = s.bean(DetailService.class).defect(s.defectId(1)).cases();
        assertThat(cases).hasSize(49).allSatisfy(c -> assertThat(c.source()).isEqualTo("merged_behavior"));
        assertThat(cases.stream().map(CaseView::defect).collect(Collectors.toSet())).hasSize(49);
    }

    @Test
    @DisplayName("MCR-D02 / SVC-CAS-13: a chain D4 into D3 into D2 shows the cases of all three on D2")
    void d02_threeLevels(Scenario s) {
        SaveResult d2 = s.newDefect("two");
        SaveResult d3 = s.newDefect("three");
        SaveResult d4 = s.newDefect("four");
        for (SaveResult d : List.of(d2, d3, d4)) {
            s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR, new RecordCaseCommand(null, d.id(),
                    "on " + d.id(), Scenario.REASON, null, null, null));
        }
        MergeFixture.merge(s, d4.id(), 1, d3.id());
        MergeFixture.merge(s, d3.id(), 1, d2.id());
        List<CaseView> cases = s.bean(DetailService.class).defect(d2.id()).cases();
        assertThat(cases.stream().filter(c -> c.source().equals("case")).map(CaseView::defect))
                .containsExactlyInAnyOrder(d2.id(), d3.id(), d4.id());
        assertThat(cases.stream().filter(c -> c.source().equals("merged_behavior")).map(CaseView::defect))
                .containsExactlyInAnyOrder(d3.id(), d4.id());
    }

    @Test
    @DisplayName("SVC-CAS-14: a case keeps evidence, quote and the observed instant, and names its recorder")
    void cas14_caseFields(Scenario s) {
        SaveResult d = s.newDefect("d");
        s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR, new RecordCaseCommand(null, d.id(), "seen",
                Scenario.REASON, "log line", "said so", "2026-11-05T18:00:00+09:00"));
        CaseView c = s.bean(DetailService.class).defect(d.id()).cases().getFirst();
        assertThat(c.evidence()).isEqualTo("log line");
        assertThat(c.userQuote()).isEqualTo("said so");
        assertThat(c.observedAt()).isEqualTo(Instant.parse("2026-11-05T09:00:00Z"));
        assertThat(c.by()).isEqualTo(Scenario.AUTHOR);
    }

    @Test
    @DisplayName("MCR-D03: imported history rows are not the last change")
    void d03_importIsIgnored(Scenario s) {
        SaveResult d = s.newDefect("d");
        IdAllocator ids = s.bean(IdAllocator.class);
        s.ctx().tx(() -> s.bean(HistoryRepository.class).insert(new HistoryEntry(ids.next(Counter.HISTORY),
                Instant.parse("2027-01-01T00:00:00Z"), new LegacyActorId("agent:claude:old"), "ledger_author",
                ActorAssurance.LEGACY_LOGIN, HistoryEntity.DEFECT, d.id(), HistoryOp.IMPORT, null, null,
                JsonDocument.EMPTY_OBJECT, null, "imported from the old ledger", null, null, null,
                new RequestId(java.util.UUID.randomUUID()), "legacy-1")));
        DefectDetail detail = s.bean(DetailService.class).defect(d.id());
        assertThat(detail.lastChange().op()).isEqualTo("CREATE");
        assertThat(detail.recentChanges()).extracting(DetailService.ChangeSummary::op).contains("IMPORT");
    }

    @Test
    @DisplayName("MCR-D04 / SVC-FIX-12: creator, owner and fixer are told apart")
    void d04_creatorOwnerFixer(Scenario s) {
        SaveResult d = s.newDefect("d");
        s.fix(Scenario.AUTHOR, d.id(), Scenario.commit('a'));
        s.fix(Scenario.REVIEWER, d.id(), Scenario.commit('b'));
        DefectDetail fixed = s.bean(DetailService.class).defect(d.id());
        assertThat(fixed.createdBy()).isEqualTo(Scenario.AUTHOR);
        assertThat(fixed.ownerActorId()).isEqualTo(Scenario.REVIEWER);
        assertThat(fixed.fixedBy()).isEqualTo(Scenario.REVIEWER);
        assertThat(fixed.fixedAt()).isNotNull();
        s.bean(ReviewService.class).review(Scenario.AUTHOR, s.move("defect", d.id(), 3, "VERIFIED",
                Scenario.EVIDENCE));
        DefectDetail verified = s.bean(DetailService.class).defect(d.id());
        assertThat(verified.lastChange().author()).isEqualTo(Scenario.AUTHOR);
        assertThat(verified.createdBy()).isEqualTo(Scenario.AUTHOR);
        assertThat(verified.ownerActorId()).isEqualTo(Scenario.REVIEWER);
    }

    @Test
    @DisplayName("MCR-D05: an unknown defect")
    void d05_notFound(Scenario s) {
        Expect.failure(() -> s.bean(DetailService.class).defect(s.defectId(9)), LookupCode.NOT_FOUND);
    }

    // -------------------------------------------------------------- task detail

    @Test
    @DisplayName("MCR-T01: a task names the session that reported it done, not the one that sent it back")
    void t01_taskDetail(Scenario s) {
        var fields = s.taskFields("t");
        fields.put("owner_actor_id", "codex:owner1");
        fields.put("origin_ref", "ref-1");
        SaveResult t = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", null, fields));
        SaveResult d = s.newDefect("d");
        SaveResult c = s.newCondition("1", "r", "c");
        s.bean(LinkService.class).link(Scenario.AUTHOR, new LinkCommand(null, "defect_task", d.id(), t.id(),
                Scenario.REASON, null));
        s.bean(LinkService.class).link(Scenario.AUTHOR, new LinkCommand(null, "condition_task", c.id(), t.id(),
                Scenario.REASON, null));
        s.bean(TransitionService.class).transition(Scenario.AUTHOR, s.move("task", t.id(), 1, "REPORTED_DONE",
                Scenario.EVIDENCE));
        s.bean(ReviewService.class).review(Scenario.REVIEWER, s.move("task", t.id(), 2, "VERIFIED",
                Scenario.EVIDENCE));
        s.bean(ReviewService.class).review(Scenario.REVIEWER, s.move("task", t.id(), 3, "REPORTED_DONE",
                Scenario.EVIDENCE));
        DetailService.TaskDetail detail = s.bean(DetailService.class).task(t.id());
        assertThat(detail.title()).isEqualTo("t");
        assertThat(detail.purpose()).isEqualTo("purpose of t");
        assertThat(detail.successCondition()).isEqualTo("success of t");
        assertThat(detail.originRef()).isEqualTo("ref-1");
        assertThat(detail.ownerActorId()).isEqualTo("codex:owner1");
        assertThat(detail.state()).isEqualTo("REPORTED_DONE");
        assertThat(detail.version()).isEqualTo(4);
        assertThat(detail.completedBy()).isEqualTo(Scenario.AUTHOR);
        assertThat(detail.defects()).containsExactly(d.id());
        assertThat(detail.conditions()).containsExactly(c.id());
    }

    @Test
    @DisplayName("MCR-T02: an unknown task")
    void t02_notFound(Scenario s) {
        Expect.failure(() -> s.bean(DetailService.class).task(s.taskId(9)), LookupCode.NOT_FOUND);
    }

    // -------------------------------------------------------------- attention

    @Test
    @DisplayName("SVC-FIX-11: a verified defect whose fix has no CI data is never an attention item")
    void fix11_noVerifiedWithoutCi(Scenario s) {
        SaveResult d = s.verifiedDefect("d");
        List<String> kinds = new ArrayList<>();
        var page = s.bean(AttentionService.class).attention(new AttentionQuery(s.projectId(), null, 1000, null));
        page.items().forEach(i -> kinds.add(i.kind() + ":" + i.id()));
        assertThat(kinds).noneMatch(k -> k.endsWith(":" + d.id()));
        assertThat(kinds).noneMatch(k -> k.startsWith("VERIFIED_WITHOUT_CI"));
        assertThat(java.util.Arrays.stream(AttentionKind.values()).map(Enum::name)).doesNotContain(
                "VERIFIED_WITHOUT_CI");
    }
}
