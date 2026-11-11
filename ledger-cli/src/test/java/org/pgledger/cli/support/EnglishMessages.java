package org.pgledger.cli.support;

import java.util.Locale;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.i18n.LocaleContextHolder;

/**
 * Makes the texts of the CLI English for the test that asserts on them: the locale of the thread is English while the test
 * runs (the application takes its locale from {@link LocaleContextHolder}; without a message bundle of that locale it uses
 * the default bundle, which is English, because {@code spring.messages.fallback-to-system-locale} is false). Tests that do
 * not assert on a text do not need it.
 */
public final class EnglishMessages implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        LocaleContextHolder.resetLocaleContext();
    }
}
