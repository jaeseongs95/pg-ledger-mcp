package org.pgledger.cli.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Runs the test only when the default file system does not support POSIX file permissions. The condition is the
 * capability of the file system, not the name of the operating system.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@ExtendWith(PosixCapabilityCondition.class)
public @interface RequiresNonPosixFileSystem {
}
