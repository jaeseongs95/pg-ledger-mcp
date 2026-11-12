package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.TrustException;
import org.pgledger.domain.rule.ToolContract;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.testing.ContractVectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SVC-PRN-08 and SVC-PRN-09: the server's side of the contract with the client hook. The hook (a Spring free jar) and the
 * server are two programs that each define the author format and the names of the tools; the hook tests against the
 * vectors of the {@code ledger-attestation} test fixtures, and this class runs the same vectors against the domain's
 * {@link AuthorId} and {@link ToolContract}, so the two definitions cannot drift apart unnoticed.
 */
class ContractVectorsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode vectors(String resource) {
        return JSON.readTree(ContractVectors.read(resource));
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(item -> out.add(item.asString()));
        return out;
    }

    @Test
    @DisplayName("SVC-PRN-08: every valid author of the vectors is an AuthorId that splits at the colon")
    void prn08_validAuthors() {
        List<String> valid = texts(vectors(ContractVectors.AUTHORS).get("valid"));
        assertThat(valid).isNotEmpty();
        for (String text : valid) {
            assertThat(AuthorId.matches(text)).as(text).isTrue();
            AuthorId author = AuthorId.of(text);
            assertThat(author.value()).isEqualTo(text);
            assertThat(author.vendor() + ":" + author.session()).as(text).isEqualTo(text);
        }
    }

    @Test
    @DisplayName("SVC-PRN-08: every invalid author of the vectors is refused: AUTHOR_ID_FORMAT naming the text, HOST_ATTESTATION_REQUIRED for none")
    void prn08_invalidAuthors() {
        JsonNode invalid = vectors(ContractVectors.AUTHORS).get("invalid");
        assertThat(invalid.size()).isGreaterThan(10);
        for (JsonNode vector : invalid) {
            JsonNode value = vector.get("value");
            String text = value.isNull() ? null : value.asString();
            String why = vector.get("why").asString();
            assertThat(AuthorId.matches(text)).as(why).isFalse();
            Throwable refused = catchThrowable(() -> AuthorId.of(text));
            assertThat(refused).as(why).isInstanceOf(TrustException.class);
            TrustException trust = (TrustException) refused;
            if (text == null) {
                assertThat(trust.code()).as(why).isEqualTo(TrustCode.HOST_ATTESTATION_REQUIRED);
            } else {
                assertThat(trust.code()).as(why).isEqualTo(TrustCode.AUTHOR_ID_FORMAT);
                assertThat(trust.args()).as(why).containsExactly(text);
            }
        }
    }

    @Test
    @DisplayName("SVC-PRN-09: the write tools and the read tools of the domain's ToolContract are the names of the vectors, in order")
    void prn09_toolNames() {
        JsonNode tools = vectors(ContractVectors.WRITE_TOOLS);
        assertThat(ToolContract.WRITE).containsExactlyElementsOf(texts(tools.get("write")));
        assertThat(ToolContract.READ).containsExactlyElementsOf(texts(tools.get("read")));
    }
}
