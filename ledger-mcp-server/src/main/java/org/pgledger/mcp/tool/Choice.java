package org.pgledger.mcp.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The value of this argument is one of the constants of closed sets of the domain. The schema carries them as its
 * enumeration and a value outside them is refused before the tool runs. A set that has a wire token uses it,
 * otherwise the constant name is the value.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface Choice {

    /** The enums whose constants make the set, in order. */
    Class<? extends Enum<?>>[] from();

    /** When not empty, only the constants of {@link #from()} with these names. */
    String[] only() default {};

    /** Whether the verification states (VERIFIED, MET, NOT_MET) are part of the set. */
    Verification verification() default Verification.INCLUDED;

    /** Treatment of the verification states of lifecycle enums. */
    enum Verification {
        INCLUDED, EXCLUDED
    }
}
