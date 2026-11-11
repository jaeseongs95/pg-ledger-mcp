package org.pgledger.application.support;

import java.util.List;
import java.util.function.BiConsumer;
import org.pgledger.application.write.BatchOpInput;
import org.pgledger.application.write.BatchService;
import org.pgledger.application.write.CorrectCommand;
import org.pgledger.application.write.CorrectService;
import org.pgledger.application.write.LinkCommand;
import org.pgledger.application.write.LinkService;
import org.pgledger.application.write.RecordCaseCommand;
import org.pgledger.application.write.RecordCaseService;
import org.pgledger.application.write.RecordFixService;
import org.pgledger.application.write.ReviewService;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.TransitionCommand;
import org.pgledger.application.write.TransitionService;
import org.pgledger.application.write.WhoamiService;
import org.pgledger.domain.rule.Channel;

/** One write service (or whoami) invoked with a given actor, against targets of a scenario. A fixture of the gate tests. */
public record WriteCall(String name, BiConsumer<Scenario, String> invoke) {

    @Override
    public String toString() {
        return name;
    }

    /** Every service that writes, and whoami. */
    public static List<WriteCall> all() {
        return List.of(
                new WriteCall("save", (s, actor) -> s.bean(SaveService.class).save(actor,
                        s.create("defect", null, s.defectFields("t")))),
                new WriteCall("transition", (s, actor) -> s.bean(TransitionService.class).transition(actor,
                        s.move("defect", s.defectId(1), 1, "DISPUTED", Scenario.EVIDENCE))),
                new WriteCall("record_fix", (s, actor) -> s.bean(RecordFixService.class).recordFix(actor,
                        s.fixCommand(s.defectId(1), Scenario.commit('a')))),
                new WriteCall("record_case", (s, actor) -> s.bean(RecordCaseService.class).recordCase(actor,
                        new RecordCaseCommand(null, s.defectId(1), "seen", Scenario.REASON, null, null, null))),
                new WriteCall("link", (s, actor) -> s.bean(LinkService.class).link(actor,
                        new LinkCommand(null, "defect_task", s.defectId(1), s.taskId(1), Scenario.REASON, null))),
                new WriteCall("correct", (s, actor) -> s.bean(CorrectService.class).correct(actor,
                        new CorrectCommand(null, 1L, Scenario.REASON))),
                new WriteCall("batch", (s, actor) -> s.bean(BatchService.class).batch(actor, null,
                        List.of(new BatchOpInput.Save("defect", s.defectFields("t"), Scenario.REASON, null, null,
                                null)))),
                new WriteCall("review", (s, actor) -> s.bean(ReviewService.class).review(actor,
                        new TransitionCommand(null, "defect", s.defectId(1), 1L, "DISPUTED", Scenario.REASON,
                                Scenario.EVIDENCE, null, null))),
                new WriteCall("review_correct", (s, actor) -> s.bean(ReviewService.class).reviewCorrect(actor,
                        new CorrectCommand(null, 1L, Scenario.REASON))),
                new WriteCall("whoami", (s, actor) -> s.bean(WhoamiService.class).whoami(actor, Channel.AUTHOR)));
    }
}
