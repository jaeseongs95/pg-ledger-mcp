package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.domain.error.ErrorCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.mcp.error.ToolError;
import org.pgledger.mcp.error.ToolErrorMapper;
import org.pgledger.mcp.support.Failures;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * Section 1.5, the error-token registry: for every constant of the seven code enums the failure of its category
 * becomes a tool error whose token is the constant, with an English message from the bundles. The mapper is the real
 * one on the real message bundles (nothing is stubbed).
 */
class ToolErrorMapperTest {

    private static ToolErrorMapper mapper() {
        ResourceBundleMessageSource bundles = new ResourceBundleMessageSource();
        bundles.setBasenames("messages/ledger-errors", "messages/ledger-reasons", "messages/mcp");
        bundles.setFallbackToSystemLocale(false);
        return new ToolErrorMapper(new MessageSourceAccessor(bundles, Locale.ENGLISH));
    }

    static Stream<ErrorCode> codes() {
        return Failures.allCodes();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("codes")
    @DisplayName("1.5: every error code is a tool error with the token of the code and an English message from the "
            + "bundle")
    void everyCodeIsAToolError(ErrorCode code) {
        ToolError error = mapper().map(Failures.of(code));

        assertThat(error.code()).isEqualTo(code.name());
        assertThat(error.message()).isNotBlank().isNotEqualTo(code.token()).doesNotContain("{").doesNotContain("}");
        assertThat(error.message()).doesNotContain("Exception").doesNotContain("org.pgledger");
    }

    static Stream<ErrorCode> validationCodes() {
        return Failures.allCodes().filter(ValidationCode.class::isInstance);
    }

    static Stream<ErrorCode> otherCodes() {
        return Failures.allCodes().filter(code -> !(code instanceof ValidationCode));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validationCodes")
    @DisplayName("1.5: a failure of a validation code lists its issues with path and reason")
    void validationListsItsIssues(ErrorCode code) {
        ToolError error = mapper().map(Failures.of(code));

        assertThat(error.issues()).containsExactly(new ToolError.Issue("some.path", "is required", List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("otherCodes")
    @DisplayName("1.5: a failure of any other code lists no issues")
    void otherFailuresListNoIssues(ErrorCode code) {
        assertThat(mapper().map(Failures.of(code)).issues()).isEmpty();
    }
}
