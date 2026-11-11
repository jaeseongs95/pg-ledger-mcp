package org.pgledger.application.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;

/** A service test class that runs once, on H2 (catalogue rows marked DBMS {@code no}). */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ParameterizedClass
@EnumSource(value = Dbms.class, names = "H2")
@ExtendWith(LedgerAppExtension.class)
public @interface H2ServiceTest {
}
