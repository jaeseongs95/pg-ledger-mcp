package org.pgledger.cli;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Boot standard path that prints a refusal as its message (and the usage text) instead of a stack trace; see META-INF/spring.factories. */
public class CliFailureAnalyzer extends AbstractFailureAnalyzer<CliException> {

    private final CliErrorTranslator translator;

    public CliFailureAnalyzer(BeanFactory beanFactory) {
        this.translator = beanFactory.getBean(CliErrorTranslator.class);
    }

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, CliException cause) {
        CliErrorTranslator.Report report = translator.translate(cause);
        return new FailureAnalysis(report.description(), report.action(), cause);
    }
}
