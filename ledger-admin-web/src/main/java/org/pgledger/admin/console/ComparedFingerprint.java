package org.pgledger.admin.console;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.pgledger.admin.web.FormErrors;

/**
 * The confirmation of a grant names the fingerprint that the owner saw and has the box that says it was compared; one
 * without the other is a missing fingerprint, reported on the one path {@code fingerprint} (the owner has one thing to
 * fix: compare and confirm). Whether the fingerprint is the one of the key needs the key, so the controller checks that.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ComparedFingerprint.Check.class)
public @interface ComparedFingerprint {

    String message() default FormErrors.REQUIRED;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** The check: both the fingerprint and the box are there. */
    final class Check implements ConstraintValidator<ComparedFingerprint, GrantsController.Confirmation> {

        @Override
        public boolean isValid(GrantsController.Confirmation confirmation, ConstraintValidatorContext context) {
            if (confirmation.fingerprint() != null && confirmation.confirmFingerprint() != null) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(FormErrors.REQUIRED).addPropertyNode("fingerprint")
                    .addConstraintViolation();
            return false;
        }
    }
}
