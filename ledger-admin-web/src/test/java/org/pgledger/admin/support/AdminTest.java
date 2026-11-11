package org.pgledger.admin.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.pgledger.admin.LedgerAdminApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * A test class of the admin web: the real application in a Spring Boot context (the whole Spring Security chain, the
 * pages, the services) on an embedded H2 database that the real schema files have built, driven through MockMvc.
 * Nothing is mocked. Spring's test context cache shares the one context between the test classes; every test works in
 * a project of its own ({@link Seeds}) and from a client address of its own ({@link Browser}), so they do not collide.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(classes = LedgerAdminApplication.class, properties = {AdminTestSettings.TOKEN_PROPERTY,
    AdminTestSettings.AGENT_TOKEN_PROPERTY, AdminTestSettings.INSECURE_COOKIE, AdminTestSettings.NO_GLOBAL_CAP,
    AdminTestSettings.QUIET_BANNER, AdminTestSettings.QUIET_LOG,
    AdminTestSettings.NO_JMX})
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Import({AdminTestInfrastructure.class, Seeds.class, ProbeController.class})
public @interface AdminTest {
}
