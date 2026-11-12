package org.pgledger.cli;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Boot standard path that reports a refusal as its message (and, for a usage error, the usage text) instead of a stack trace;
 * registered in META-INF/spring.factories. The translator is looked up only when a refusal actually occurs, so an unrelated
 * startup failure is never affected by this analyzer.
 *
 * <p>Why {@code getBean} and no injected translator (principle 31): the analyzer is not a bean. Boot creates it from
 * {@code spring.factories} when the startup has already failed, and offers a {@link BeanFactory} as the only thing a
 * constructor can take, so the translator can only be looked up.
 */
public class CliFailureAnalyzer extends AbstractFailureAnalyzer<CliException> {

    private final BeanFactory beanFactory;

    public CliFailureAnalyzer(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, CliException cause) {
        CliErrorTranslator.Report report = beanFactory.getBean(CliErrorTranslator.class).translate(cause);
        return new FailureAnalysis(report.description(), report.action(), cause);
    }
}
