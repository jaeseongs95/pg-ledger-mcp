package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.admin.app.LedgerSourceProperties;
import org.pgledger.admin.support.AdminTest;
import org.pgledger.admin.support.Browser;
import org.pgledger.admin.support.FailureText;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * W7-AGPL-01: the admin web offers the source code of the program (AGPL section 13): a footer link on every page, in English
 * and in Korean, to the address of {@code ledger.source.url} (the public repository of the project by default); the setting
 * accepts an http or https address and nothing else.
 */
@AdminTest
class SourceLinkTest {

    private static final String DEFAULT_URL = "https://github.com/jaeseongs95/pg-ledger-mcp";

    @Autowired
    private MockMvc mvc;

    static Stream<String[]> pagesAndWords() {
        return Stream.of(new String[] {"en", "Source code"}, new String[] {"ko", "소스 코드"});
    }

    @ParameterizedTest
    @MethodSource("pagesAndWords")
    @DisplayName("W7-AGPL-01 the login page and the pages of the console (lists, grants, the driver page) have the footer link to the source code, with the default address, in the language of the request")
    void everyPageHasTheLink(String language, String text) throws Exception {
        Browser anonymous = new Browser(mvc);
        Browser owner = Browser.signedIn(mvc);
        String login = anonymous.perform(anonymous.get("/login").header(HttpHeaders.ACCEPT_LANGUAGE, language)).andReturn()
                .getResponse().getContentAsString();
        assertThat(login).as("/login").contains("href=\"" + DEFAULT_URL + "\"").contains(">" + text + "</a>")
                .contains("rel=\"noopener noreferrer\"");

        for (String path : new String[] {"/defects", "/projects", "/grants", "/driver"}) {
            String page = owner.perform(owner.get(path).header(HttpHeaders.ACCEPT_LANGUAGE, language)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(page).as(path).contains("href=\"" + DEFAULT_URL + "\"").contains(">" + text + "</a>")
                    .contains("rel=\"noopener noreferrer\"");
        }
    }

    @Test
    @DisplayName("W7-AGPL-01 the default of the setting is the public repository of the project")
    void theDefaultIsTheRepository() {
        assertThat(LedgerSourceProperties.PUBLIC_REPOSITORY).isEqualTo(DEFAULT_URL);
    }

    @EnableConfigurationProperties(LedgerSourceProperties.class)
    static class PropertiesOnly {
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(PropertiesOnly.class);
    }

    @Test
    @DisplayName("W7-AGPL-01 another address of the setting is the address of the link; without a setting it is the default")
    void theSettingIsTheAddress() {
        runner().withPropertyValues("ledger.source.url=https://example.test/my-fork").run(context -> assertThat(context
                .getBean(LedgerSourceProperties.class).url()).isEqualTo("https://example.test/my-fork"));
        runner().run(context -> assertThat(context.getBean(LedgerSourceProperties.class).url()).isEqualTo(DEFAULT_URL));
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "ftp://example.test/source", "example.test/source", "http:///source",
        "https://user:secret-pw-0123@example.test/source", " "})
    @DisplayName("W7-AGPL-01 an address that is no http or https address with a host and no user part stops the start, names the setting and prints no value")
    void anUnusableAddressStopsTheStart(String address) {
        runner().withPropertyValues("ledger.source.url=" + address).run(context -> {
            assertThat(context).hasFailed();
            String shown = FailureText.of(context.getStartupFailure());
            assertThat(shown).contains("ledger.source.url").doesNotContain("secret-pw-0123").doesNotContain("Value: ");
        });
    }
}
