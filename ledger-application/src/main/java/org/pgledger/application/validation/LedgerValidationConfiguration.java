package org.pgledger.application.validation;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.validation.beanvalidation.MethodValidationExcludeFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.MethodValidationInterceptor;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

/**
 * Method validation of the services: a service class is {@code @Validated}, its input records carry Jakarta
 * Validation constraints and its parameters are {@code @Valid}. A violation leaves this module as the domain's
 * {@link org.pgledger.domain.error.ValidationException}; the translation is the {@link ViolationTranslator}. The
 * advice runs before the transaction of the service is opened, so an invalid request touches no connection. Spring
 * Boot's own post processor backs off because this one is a bean of the same type; the {@link Validator} still comes
 * from Boot's validation auto-configuration.
 */
@Configuration(proxyBeanMethods = false)
public class LedgerValidationConfiguration {

    /**
     * A bean method and not a component: Boot's own post processor makes the advice that raises Spring's
     * {@code ConstraintViolationException}, and the only way to raise the domain's failure instead is to replace that
     * advice, which is done by subclassing the processor. The method is static so that the processor exists before the
     * configuration class does (a {@code BeanPostProcessor} bean method).
     */
    @Bean
    static MethodValidationPostProcessor methodValidationPostProcessor(ObjectProvider<Validator> validator,
            ObjectProvider<MethodValidationExcludeFilter> excludeFilters) {
        List<MethodValidationExcludeFilter> filters = new ArrayList<>(excludeFilters.orderedStream().toList());
        // a @Validated settings record is validated when Boot binds it; it must not become a method-validation proxy
        filters.add(MethodValidationExcludeFilter.byAnnotation(ConfigurationProperties.class));
        MethodValidationPostProcessor processor = new TranslatingPostProcessor(filters);
        processor.setValidatorProvider(validator);
        processor.setProxyTargetClass(true);
        processor.setBeforeExistingAdvisors(true);
        return processor;
    }

    /** The post processor of Spring's method validation whose advice raises the domain's failure. */
    static final class TranslatingPostProcessor extends MethodValidationPostProcessor {

        private static final long serialVersionUID = 1L;

        private final transient List<MethodValidationExcludeFilter> excludeFilters;

        /** Made with {@code new} here: see {@link ViolationTranslator} for why it is no bean. */
        private final transient ViolationTranslator translator = new ViolationTranslator();

        TranslatingPostProcessor(List<MethodValidationExcludeFilter> excludeFilters) {
            this.excludeFilters = List.copyOf(excludeFilters);
        }

        @Override
        protected boolean isEligible(Object bean, String beanName) {
            Class<?> target = AopUtils.getTargetClass(bean);
            return excludeFilters.stream().noneMatch(filter -> filter.isExcluded(target))
                    && super.isEligible(bean, beanName);
        }

        @Override
        protected Advice createMethodValidationAdvice(Supplier<Validator> validator) {
            MethodValidationInterceptor validation = new MethodValidationInterceptor(validator);
            return (MethodInterceptor) invocation -> {
                try {
                    return validation.invoke(invocation);
                } catch (ConstraintViolationException violations) {
                    throw translator.translate(violations.getConstraintViolations(), violations);
                }
            };
        }
    }
}
