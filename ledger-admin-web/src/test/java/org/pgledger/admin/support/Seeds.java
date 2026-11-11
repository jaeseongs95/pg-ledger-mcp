package org.pgledger.admin.support;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.pgledger.application.admin.GrantService;
import org.pgledger.application.admin.GrantService.GrantCommand;
import org.pgledger.application.admin.ProjectService;
import org.pgledger.application.admin.ProjectService.ProjectCommand;
import org.pgledger.application.admin.SessionKeyService;
import org.pgledger.application.admin.SessionKeyService.RegisterCommand;
import org.pgledger.application.write.RecordCaseCommand;
import org.pgledger.application.write.RecordCaseService;
import org.pgledger.application.write.SaveCommand;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.TransitionCommand;
import org.pgledger.application.write.TransitionService;
import org.pgledger.attestation.ArgumentsDigest;
import org.pgledger.attestation.AttestationClaims;
import org.pgledger.attestation.AttestationToken;
import org.pgledger.attestation.PublicKeys;
import org.pgledger.domain.context.ToolScope;
import org.springframework.stereotype.Component;
import org.pgledger.testing.TestOwners;

/**
 * Rows for the page tests, made through the real services in a project of its own (so tests do not collide): the
 * author of the scenario holds the defect and task grants, exactly as an installation does. Every write carries the
 * signed attestation the write gate demands: the author has an Ed25519 key pair whose public key is registered once
 * through {@link SessionKeyService} (as the owner does), and each write is made inside the tool scope of the tool the
 * real call uses, with a token signed for that tool. The real verifier checks it; nothing stands in for it.
 */
@Component
public class Seeds {

    public static final String AUTHOR = "claude:admin-test-author";
    public static final String REASON = "a reason that is long enough";

    /** A project and what was made in it. */
    public record Seeded(String project, String code) {

        public String defectId(int number) {
            return code + "-D-" + "0".repeat(4 - Integer.toString(number).length()) + number;
        }

        public String taskId(int number) {
            return code + "-T-" + "0".repeat(4 - Integer.toString(number).length()) + number;
        }
    }

    private final AtomicInteger numbers = new AtomicInteger();
    private final ProjectService projects;
    private final GrantService grants;
    private final SaveService saves;
    private final TransitionService transitions;
    private final RecordCaseService cases;
    private final MutableClock clock;
    private final SessionKeyService keys;
    private final KeyPair pair = newPair();
    private boolean enrolled;

    public Seeds(ProjectService projects, GrantService grants, SaveService saves, TransitionService transitions,
            RecordCaseService cases, MutableClock clock, SessionKeyService keys) {
        this.projects = projects;
        this.grants = grants;
        this.saves = saves;
        this.transitions = transitions;
        this.cases = cases;
        this.clock = clock;
        this.keys = keys;
    }

    /** Moves the clock on by a second, so rows made one after the other have different times. */
    public void tick() {
        clock.advance(java.time.Duration.ofSeconds(1));
    }

    /** A new project of its own where the author may write defects and tasks. */
    public Seeded project() {
        int number = numbers.incrementAndGet();
        Seeded seeded = new Seeded("adm" + number, "ADM" + number);
        projects.add(new ProjectCommand(seeded.project(), seeded.code(), "Project " + number), TestOwners.OWNER);
        grants.grant(new GrantCommand(AUTHOR, seeded.project(), List.of("defect", "task"), null, null), TestOwners.OWNER);
        return seeded;
    }

    public String defect(Seeded seeded, String title) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("project_id", seeded.project());
        fields.put("title", title);
        fields.put("category", "ARC");
        fields.put("severity", "P2");
        fields.put("behavior", "behavior of " + title);
        fields.put("root_cause", "cause of " + title);
        return signed("ledger_save", attestation -> saves.save(attestation,
                new SaveCommand(null, "defect", null, 0L, fields, REASON))).id();
    }

    public String task(Seeded seeded, String title) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("project_id", seeded.project());
        fields.put("title", title);
        fields.put("purpose", "purpose of " + title);
        fields.put("success_condition", "success of " + title);
        return signed("ledger_save", attestation -> saves.save(attestation,
                new SaveCommand(null, "task", null, 0L, fields, REASON))).id();
    }

    public String condition(Seeded seeded, String suffix, String release, String conditionCode) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("project_id", seeded.project());
        fields.put("release", release);
        fields.put("code", conditionCode);
        fields.put("condition_text", "condition " + conditionCode);
        return signed("ledger_save", attestation -> saves.save(attestation, new SaveCommand(null, "condition",
                seeded.code() + "-C-" + suffix, 0L, fields, REASON))).id();
    }

    /** Moves an OPEN defect (version 1) to DISPUTED, with the given evidence text in the history row. */
    public void dispute(String defect, String evidence) {
        signed("ledger_transition", attestation -> transitions.transition(attestation, new TransitionCommand(null,
                "defect", defect, 1L, "DISPUTED", REASON, evidence, null, null)));
    }

    /** Records an observation of a defect with the given evidence text. */
    public void observe(String defect, String observation, String evidence) {
        signed("ledger_record_case", attestation -> cases.recordCase(attestation,
                new RecordCaseCommand(null, defect, observation, REASON, evidence, null, null)));
    }

    /**
     * Runs the write as the hook of the author would: in the scope of the tool named {@code tool} and with a token
     * signed for that tool, the current time of the test clock and the empty request id (the seeds send none).
     */
    private <R> R signed(String tool, Function<String, R> write) {
        enroll();
        // the seeds call the services directly, without the tool binding that digests the raw arguments of a call: they
        // sign and bind the digest of an empty arguments object (token pgla2 covers the arguments digest)
        String digest = ArgumentsDigest.of(Map.of());
        String token = AttestationToken.sign(new AttestationClaims(AUTHOR, tool, "", digest,
                clock.instant().getEpochSecond()), pair.getPrivate()).text();
        return ScopedValue.where(ToolScope.CURRENT, tool).where(ToolScope.ARGUMENTS_DIGEST, digest)
                .call(() -> write.apply(token));
    }

    /** Registers the public key of the author once, as the owner does with the key the hook printed. */
    private synchronized void enroll() {
        if (!enrolled) {
            keys.register(new RegisterCommand(AUTHOR, PublicKeys.encode(pair.getPublic()), null), TestOwners.OWNER);
            enrolled = true;
        }
    }

    private static KeyPair newPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("This JDK has no Ed25519 key pair generator", failure);
        }
    }
}
