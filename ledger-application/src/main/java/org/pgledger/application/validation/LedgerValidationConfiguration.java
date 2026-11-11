package org.pgledger.application.validation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import jakarta.validation.Validator;
import java.util.ArrayList;
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
import org.pgledger.domain.error.ValidationReason;
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
 * {@link ValidationException} (code VALIDATION_FAILED, the wire name of the property as the path); the
 * translation happens here and nowhere else. The advice runs before the transaction of the service is opened, so
 * an invalid request touches no connection. Spring Boot's own post processor backs off because this one is a bean
 * of the same type; the {@link Validator} still comes from Boot's validation auto-configuration.
 */
@Configuration(proxyBeanMethods = false)
public class LedgerValidationConfiguration {

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
                    throw translate(violations.getConstraintViolations(), violations);
                }
            };
        }
    }

    /**
     * One issue per violated property, in the order of their paths; the first one names the failure. The reason is
     * the one the message template of the constraint names ({@code {reason.REQUIRED}}), its arguments are attributes
     * of the constraint ({@code max} of a size); the interpolated message of the violation is never read, so the
     * result does not depend on the locale of the JVM and the sentence is the business of the reason bundles.
     */
    static ValidationException translate(Set<ConstraintViolation<?>> violations, Throwable cause) {
        List<ValidationIssue> issues = violations.stream()
                .map(violation -> {
                    ValidationReason reason = ValidationReason.ofTemplate(violation.getMessageTemplate());
                    return new ValidationIssue(path(violation.getPropertyPath()), reason,
                            reasonArgs(reason, violation));
                })
                .distinct()
                .sorted(Comparator.comparing(ValidationIssue::path).thenComparing(ValidationIssue::reason))
                .toList();
        ValidationIssue first = issues.getFirst();
        return new ValidationException(ValidationCode.VALIDATION_FAILED, List.of(first.path()), issues, Map.of(),
                cause);
    }

    /** The arguments of a reason: the constraint attributes its pattern names, in the order of its placeholders. */
    private static List<String> reasonArgs(ValidationReason reason, ConstraintViolation<?> violation) {
        Map<String, Object> attributes = violation.getConstraintDescriptor().getAttributes();
        return switch (reason) {
            case MUST_BE_AT_MOST_CHARS -> List.of(String.valueOf(attributes.get("max")));
            case MUST_BE_CHARS_RANGE -> List.of(String.valueOf(attributes.get("min")),
                    String.valueOf(attributes.get("max")));
            default -> List.of();
        };
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
