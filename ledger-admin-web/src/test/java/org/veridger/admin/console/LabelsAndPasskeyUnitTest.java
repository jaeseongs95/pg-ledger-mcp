// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.unit.DataSize;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.veridger.admin.app.LedgerAdminProperties;
import org.veridger.admin.app.SignInMethod;
import org.veridger.admin.passkey.PasskeyRelyingParty;
import org.veridger.admin.web.AdminProblems;
import org.veridger.application.read.CatalogService.CodeView;
import org.veridger.domain.error.AdminCode;
import org.veridger.domain.error.AdminException;
import org.veridger.domain.error.ConflictCode;
import org.veridger.domain.error.ConflictException;
import org.veridger.domain.error.InfrastructureCode;
import org.veridger.domain.error.InfrastructureException;
import org.veridger.domain.error.LookupCode;
import org.veridger.domain.error.LookupException;
import org.veridger.domain.error.TransitionCode;
import org.veridger.domain.error.TransitionException;
import org.veridger.domain.error.TrustCode;
import org.veridger.domain.error.TrustException;
import org.veridger.domain.error.ValidationCode;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.error.ValidationReason;
import org.veridger.domain.rule.CodeState;
import org.springframework.http.HttpStatus;
import org.springframework.util.SimpleIdGenerator;

/**
 * Exhaustive unit tests for {@link Labels} and {@link PasskeyRelyingParty} covering all enum/code localization branches,
 * request-scoped cache hits/misses, locale fallbacks, and WebAuthn relying-party / origin validation rules.
 */
class LabelsAndPasskeyUnitTest {

    @AfterEach
    void cleanContext() {
        LocaleContextHolder.resetLocaleContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("ADM-LBL-01 Labels resolves every enum, history entity/state, locale fallback, and request code cache")
    void labelsCoversAllEnumAndCodeBranches() {
        StaticMessageSource messages = new StaticMessageSource();
        messages.addMessage("issue.status.OPEN", Locale.ENGLISH, "Open");
        messages.addMessage("spec.status.DRAFT", Locale.ENGLISH, "Draft");
        messages.addMessage("task.state.PLANNED", Locale.ENGLISH, "Planned");
        messages.addMessage("criterion.state.NOT_MET", Locale.ENGLISH, "Not Met");
        messages.addMessage("entity.issue", Locale.ENGLISH, "Issue");
        messages.addMessage("history.op.CREATE", Locale.ENGLISH, "Created");
        messages.addMessage("codes.group.ISSUE_SEVERITY", Locale.ENGLISH, "Severity");
        messages.addMessage("codes.state.ACTIVE", Locale.ENGLISH, "Active");
        messages.addMessage("deliverable.checkResult.passed", Locale.ENGLISH, "Passed");
        messages.addMessage("grants.scope.issue", Locale.ENGLISH, "Issue Scope");

        List<CodeView> sampleCodes = List.of(
                new CodeView("PRIORITY", "P1", "긴급", "Urgent", null, null, 1, CodeState.ACTIVE, null),
                new CodeView("PRIORITY", "P2", "높음", "", null, null, 2, CodeState.ACTIVE, null),
                new CodeView("PRIORITY", "P3", "", "Low", null, null, 3, CodeState.ACTIVE, null),
                new CodeView("PRIORITY", "P4", "", "", null, null, 4, CodeState.ACTIVE, null));
        Choices choices = new Choices(null, null) {
            @Override
            public List<CodeView> allCodes() {
                return sampleCodes;
            }
        };
        Labels labels = new Labels(messages, choices);

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(labels.issueStatus(null)).isEmpty();
        assertThat(labels.issueStatus("OPEN")).isEqualTo("Open");
        assertThat(labels.issueStatus("UNKNOWN")).isEqualTo("UNKNOWN");

        assertThat(labels.specStatus("")).isEmpty();
        assertThat(labels.specStatus("DRAFT")).isEqualTo("Draft");
        assertThat(labels.specStatus("UNKNOWN")).isEqualTo("UNKNOWN");

        assertThat(labels.taskState(" ")).isEmpty();
        assertThat(labels.taskState("PLANNED")).isEqualTo("Planned");
        assertThat(labels.taskState("UNKNOWN")).isEqualTo("UNKNOWN");

        assertThat(labels.criterionState(null)).isEmpty();
        assertThat(labels.criterionState("NOT_MET")).isEqualTo("Not Met");
        assertThat(labels.criterionState("UNKNOWN")).isEqualTo("UNKNOWN");

        assertThat(labels.entity(null)).isEmpty();
        assertThat(labels.entity("issue")).isEqualTo("Issue");
        assertThat(labels.entity("unknown")).isEqualTo("unknown");

        assertThat(labels.historyOp(null)).isEmpty();
        assertThat(labels.historyOp("CREATE")).isEqualTo("Created");
        assertThat(labels.historyOp("UNKNOWN")).isEqualTo("UNKNOWN");

        assertThat(labels.historyState("issue", "")).isEmpty();
        assertThat(labels.historyState("unknown", "OPEN")).isEqualTo("OPEN");
        assertThat(labels.historyState("issue", "OPEN")).isEqualTo("Open");
        assertThat(labels.historyState("spec", "DRAFT")).isEqualTo("Draft");
        assertThat(labels.historyState("task", "PLANNED")).isEqualTo("Planned");
        assertThat(labels.historyState("criterion", "NOT_MET")).isEqualTo("Not Met");
        assertThat(labels.historyState("deliverable", "ANY")).isEqualTo("ANY");

        assertThat(labels.codeGroup(null)).isEmpty();
        assertThat(labels.codeGroup("ISSUE_SEVERITY")).isEqualTo("Severity");
        assertThat(labels.codeGroup("UNKNOWN")).isEqualTo("UNKNOWN");

        assertThat(labels.codeState(null)).isEmpty();
        assertThat(labels.codeState("ACTIVE")).isEqualTo("Active");
        assertThat(labels.codeState("UNKNOWN")).isEqualTo("UNKNOWN");

        assertThat(labels.checkResult(null)).isEmpty();
        assertThat(labels.checkResult("passed")).isEqualTo("Passed");
        assertThat(labels.checkResult("unknown")).isEqualTo("unknown");

        assertThat(labels.scope(null)).isEmpty();
        assertThat(labels.scope("issue")).isEqualTo("Issue Scope");
        assertThat(labels.scope("unknown")).isEqualTo("unknown");

        // CodeView localization in English (en -> ko -> raw) and Korean (ko -> en -> raw)
        assertThat(labels.code(null)).isEmpty();
        assertThat(labels.code(sampleCodes.get(0))).isEqualTo("Urgent");
        assertThat(labels.code(sampleCodes.get(1))).isEqualTo("높음");
        assertThat(labels.code(sampleCodes.get(2))).isEqualTo("Low");
        assertThat(labels.code(sampleCodes.get(3))).isEqualTo("P4");

        // codeName without RequestAttributes, blank code, blank group
        assertThat(labels.codeName("PRIORITY", "")).isEmpty();
        assertThat(labels.codeName("", "P1")).isEqualTo("P1");
        assertThat(labels.codeName("PRIORITY", "P1")).isEqualTo("Urgent");
        assertThat(labels.codeName("PRIORITY", "MISSING")).isEqualTo("MISSING");

        // With RequestAttributes (cache miss then cache hit) in Korean locale
        LocaleContextHolder.setLocale(Locale.KOREAN);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        assertThat(labels.code(sampleCodes.get(0))).isEqualTo("긴급");
        assertThat(labels.code(sampleCodes.get(2))).isEqualTo("Low");
        assertThat(labels.code(sampleCodes.get(3))).isEqualTo("P4");
        assertThat(labels.codeName("PRIORITY", "P1")).isEqualTo("긴급");
        assertThat(labels.codeName("PRIORITY", "P2")).isEqualTo("높음");
    }

    @Test
    @DisplayName("ADM-PK-01 PasskeyRelyingParty derives and validates rpId, port, HTTPS, and subdomain origins")
    void passkeyRelyingPartyValidationBranches() {
        ServerProperties defaultServer = new ServerProperties();
        PasskeyRelyingParty loopback = new PasskeyRelyingParty(
                adminProps(List.of("127.0.0.1", "localhost", "::1"), null, List.of()), defaultServer);
        assertThat(loopback.rpId()).isEqualTo("localhost");
        assertThat(loopback.rpName()).isEqualTo("Ledger admin");
        assertThat(loopback.origins()).containsExactly("http://localhost:8865");

        ServerProperties customPort = new ServerProperties();
        customPort.setPort(9443);
        PasskeyRelyingParty singlePublic = new PasskeyRelyingParty(
                adminProps(List.of("127.0.0.1", "ADMIN.EXAMPLE.COM"), null, List.of()), customPort);
        assertThat(singlePublic.rpId()).isEqualTo("admin.example.com");
        assertThat(singlePublic.origins()).containsExactly("https://admin.example.com");

        PasskeyRelyingParty subdomainOrigin = new PasskeyRelyingParty(
                adminProps(List.of("a.example.com", "b.example.com"), "EXAMPLE.COM",
                        List.of("https://a.example.com", "https://example.com")),
                customPort);
        assertThat(subdomainOrigin.rpId()).isEqualTo("example.com");
        assertThat(subdomainOrigin.origins()).containsExactlyInAnyOrder("https://a.example.com", "https://example.com");

        // Multiple public hosts without explicit rp-id
        assertThatThrownBy(() -> new PasskeyRelyingParty(
                adminProps(List.of("a.example.com", "b.example.com"), null, List.of()), defaultServer))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rp-id must be set");

        // IPv4 and IPv6/colon rp-id rejected
        assertThatThrownBy(() -> new PasskeyRelyingParty(
                adminProps(List.of("127.0.0.1"), "127.0.0.1", List.of()), defaultServer))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never an address");
        assertThatThrownBy(() -> new PasskeyRelyingParty(
                adminProps(List.of("127.0.0.1"), "example.com:443", List.of()), defaultServer))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never an address");

        // Invalid origin URI syntax, non-HTTPS public origin, path/query/userinfo, mismatched host
        assertThatThrownBy(() -> new PasskeyRelyingParty(
                adminProps(List.of("example.com"), "example.com", List.of("https://bad host")), defaultServer))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not an origin");
        assertThatThrownBy(() -> new PasskeyRelyingParty(
                adminProps(List.of("example.com"), "example.com", List.of("http://example.com")), defaultServer))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must use https");
        assertThatThrownBy(() -> new PasskeyRelyingParty(
                adminProps(List.of("example.com"), "example.com", List.of("https://example.com/path")), defaultServer))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheme, host and port only");
        assertThatThrownBy(() -> new PasskeyRelyingParty(
                adminProps(List.of("example.com"), "example.com", List.of("https://example.com?q=1")), defaultServer))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheme, host and port only");
        assertThatThrownBy(() -> new PasskeyRelyingParty(
                adminProps(List.of("example.com"), "example.com", List.of("https://user@example.com")), defaultServer))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheme, host and port only");
        assertThatThrownBy(() -> new PasskeyRelyingParty(
                adminProps(List.of("example.com"), "example.com", List.of("https://evil.org")), defaultServer))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not ledger.admin.passkey.rp-id");
    }

    @Test
    @DisplayName("ADM-GRT-01 GrantsController AuthorGrantForm and RevokeForm split/resolve cover all host/session/author branches")
    void grantsFormSplitAndResolveBranches() {
        List<String> knownHosts = List.of("codex", "claude", "gemini");

        GrantsController.AuthorGrantForm withHostAndSession = new GrantsController.AuthorGrantForm(
                null, "codex", null, "s1", "p1", List.of("issue"), null, null);
        assertThat(withHostAndSession.split(knownHosts).hostSelect()).isEqualTo("codex");
        assertThat(withHostAndSession.resolvedAuthor()).isEqualTo("codex:s1");

        GrantsController.AuthorGrantForm withCustomAndAuthor = new GrantsController.AuthorGrantForm(
                "claude:s2", null, "custom", null, "p1", List.of("issue"), null, null);
        assertThat(withCustomAndAuthor.split(knownHosts).hostSelect()).isEqualTo("claude");

        GrantsController.AuthorGrantForm sessionOnly = new GrantsController.AuthorGrantForm(
                null, "", "myhost", "s3", "p1", List.of("issue"), null, null);
        assertThat(sessionOnly.split(knownHosts).sessionId()).isEqualTo("s3");

        GrantsController.AuthorGrantForm emptyGrant = new GrantsController.AuthorGrantForm(
                null, null, null, null, "p1", List.of(), null, null);
        assertThat(emptyGrant.split(knownHosts).sessionId()).isEmpty();

        GrantsController.RevokeForm rHostAndSession = new GrantsController.RevokeForm(
                null, "codex", null, "s1", "p1", "issue");
        assertThat(rHostAndSession.split(knownHosts).hostSelect()).isEqualTo("codex");
        assertThat(rHostAndSession.resolvedAuthor()).isEqualTo("codex:s1");

        GrantsController.RevokeForm rAuthor = new GrantsController.RevokeForm(
                "gemini:s2", null, "x", null, "p1", "issue");
        assertThat(rAuthor.split(knownHosts).hostSelect()).isEqualTo("gemini");

        GrantsController.RevokeForm rSessionOnly = new GrantsController.RevokeForm(
                null, " ", "c", "s3", "p1", "issue");
        assertThat(rSessionOnly.split(knownHosts).sessionId()).isEqualTo("s3");

        GrantsController.RevokeForm rEmpty = new GrantsController.RevokeForm(
                "", "", null, "", "p1", "issue");
        assertThat(rEmpty.split(knownHosts).sessionId()).isEmpty();
    }

    @Test
    @DisplayName("ADM-PRB-01 AdminProblems maps every LedgerException subtype, AdminCode, and InfrastructureCode to RFC 9457 status")
    void adminProblemsMapsAllExceptionTypesAndCodes() {
        AdminProblems problems = new AdminProblems(new StaticMessageSource(), new SimpleIdGenerator());
        assertThat(problems.newTraceId()).isNotBlank();
        assertThat(problems.problem(HttpStatus.FORBIDDEN, AdminProblems.FORBIDDEN, Locale.ENGLISH).getStatus())
                .isEqualTo(403);

        assertThat(problems.problem(ValidationException.invalid("field", ValidationReason.REQUIRED), Locale.ENGLISH)
                .getStatus()).isEqualTo(400);
        assertThat(problems.problem(new ValidationException(ValidationCode.REASON_INVALID, List.of(), List.of(),
                java.util.Map.of("k", List.of("v"))), Locale.ENGLISH).getProperties()).containsKey("details");
        assertThat(problems.problem(new TrustException(TrustCode.SESSION_NOT_GRANTED, List.of()), Locale.ENGLISH)
                .getStatus()).isEqualTo(403);
        assertThat(problems.problem(new TransitionException(TransitionCode.TRANSITION_INVALID, List.of()), Locale.ENGLISH)
                .getStatus()).isEqualTo(409);
        assertThat(problems.problem(new ConflictException(ConflictCode.VERSION_CONFLICT, List.of()), Locale.ENGLISH)
                .getStatus()).isEqualTo(409);
        assertThat(problems.problem(new LookupException(LookupCode.NOT_FOUND, List.of()), Locale.ENGLISH)
                .getStatus()).isEqualTo(404);

        for (AdminCode code : AdminCode.values()) {
            assertThat(problems.problem(new AdminException(code, List.of()), Locale.ENGLISH).getStatus())
                    .isBetween(400, 499);
        }
        for (InfrastructureCode code : InfrastructureCode.values()) {
            assertThat(problems.problem(new InfrastructureException(code, List.of()), Locale.ENGLISH).getStatus())
                    .isBetween(400, 599);
        }
    }

    private static LedgerAdminProperties adminProps(List<String> allowedHosts, String rpId, List<String> origins) {
        return new LedgerAdminProperties(
                "admin-token-0123456789",
                "agent-token-0123456789",
                allowedHosts,
                DataSize.ofKilobytes(64),
                Path.of("/tmp/driver.yaml"),
                Path.of("/tmp/drivers"),
                new LedgerAdminProperties.Lockout(Duration.ofSeconds(60), 50, Duration.ofSeconds(1)),
                new LedgerAdminProperties.Oidc(null, null),
                new LedgerAdminProperties.Mcp(null, Duration.ofSeconds(2)),
                SignInMethod.PASSKEY,
                new LedgerAdminProperties.Passkey(rpId, origins, "Ledger admin"),
                new LedgerAdminProperties.Update(true, "jaeseongs95/veridger", "https://api.github.com",
                        List.of("github.com"), Path.of("/tmp/update"), Duration.ofSeconds(10), "4.3.1", 13));
    }
}
