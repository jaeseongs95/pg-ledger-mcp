package org.pgledger.mcp.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.pgledger.domain.rule.CodeGroup;

/**
 * The value of this argument (or record component) is a code of a common-code group. The catalog fills the
 * enumeration and the description of its schema with the active codes of the group, at start and whenever the
 * codes change. {@link #fixed()} lists values that are always allowed in front of the codes.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface CommonCode {

    CodeGroup group();

    String[] fixed() default {};
}
