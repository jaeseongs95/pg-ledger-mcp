package org.pgledger.application.validation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import jakarta.validation.Validator;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationIssue;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.MethodValidationInterceptor;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

/**
 * Method validation of the services: a service class is {@code @Validated}, its input records carry Jakarta
 * Validation constraints and its parameters are {@code @Valid}. A violation leaves this module as the domain's
 * {@link ValidationException} (code VALIDATION_FAILED, the wire name of the property as the path); the
 * translation happens here and nowhere else. The advice runs before the transaction of the service is opened, so
 * an invalid request touches no connection. Spring Boot's own post processor backs off because this one is a bean
 * of the same type; the {@link Validator} still comes from Boot's validation auto-configuration.
 */
@Configuration(proxyBeanMethods = false)
public class LedgerValidationConfiguration {

    @Bean
    static MethodValidationPostProcessor methodValidationPostProcessor(ObjectProvider<Validator> validator) {
        MethodValidationPostProcessor processor = new TranslatingPostProcessor();
        processor.setValidatorProvider(validator);
        processor.setProxyTargetClass(true);
        processor.setBeforeExistingAdvisors(true);
        return processor;
    }

    /** The post processor of Spring's method validation whose advice raises the domain's failure. */
    static final class TranslatingPostProcessor extends MethodValidationPostProcessor {

        private static final long serialVersionUID = 1L;

        @Override
        protected Advice createMethodValidationAdvice(Supplier<Validator> validator) {
            MethodValidationInterceptor validation = new MethodValidationInterceptor(validator);
            return (MethodInterceptor) invocation -> {
                try {
                    return validation.invoke(invocation);
                } catch (ConstraintViolationException violations) {
                    throw translate(violations.getConstraintViolations());
                }
            };
        }
    }

    /** One issue per violated property, in the order of their paths; the first one names the failure. */
    static ValidationException translate(Set<ConstraintViolation<?>> violations) {
        List<ValidationIssue> issues = violations.stream()
                .map(violation -> new ValidationIssue(path(violation.getPropertyPath()), violation.getMessage(),
                        List.of()))
                .distinct()
                .sorted(Comparator.comparing(ValidationIssue::path).thenComparing(ValidationIssue::reason))
                .toList();
        ValidationIssue first = issues.getFirst();
        return new ValidationException(ValidationCode.VALIDATION_FAILED, List.of(first.path(), first.reason()),
                issues, Map.of());
    }

    /** The wire name of a property path: the property nodes only, in snake case ({@code maxRows} is max_rows). */
    static String path(Path propertyPath) {
        StringBuilder wire = new StringBuilder();
        for (Path.Node node : propertyPath) {
            if (node.getKind() == ElementKind.PROPERTY && node.getName() != null) {
                if (!wire.isEmpty()) {
                    wire.append('.');
                }
                wire.append(snake(node.getName()));
            }
        }
        return wire.toString();
    }

    private static String snake(String camel) {
        StringBuilder out = new StringBuilder();
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) {
                out.append('_').append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
