plugins {
    // Spring Boot Gradle plugin: bootJar builds the one executable jar (java -jar ledger-admin-web.jar); the version is the catalog Boot version.
    id("org.springframework.boot") version libs.versions.springBoot.get()
}

dependencies {
    // The ledger services (projects, grants, common codes, read services) with the database module and the domain.
    implementation(project(":ledger-application"))
    // The Ed25519 key primitives of the author attestation: the grant screen shows the fingerprint of the key it registers (and the tests sign as the hook does).
    implementation(project(":ledger-attestation"))
    // Spring MVC with server-side rendering by Thymeleaf; the CSRF token is added to th:action forms by Spring Security.
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    // Login, session, CSRF, Host allow list (firewall), lockout and the security headers in one Spring Security chain.
    implementation("org.springframework.boot:spring-boot-starter-security")
    // The bearer token filter of Spring Security (BearerTokenAuthenticationFilter and its token resolver) reads the admin token of API clients; no JWT is used.
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    // OIDC oauth2Login: the provider is configuration (spring.security.oauth2.client.*), no provider is named in code.
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    // RestClient (with its builder auto-configuration) for the MCP health check of the overview.
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    // /healthz is the Actuator health endpoint.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // The failed-login counters and locks are Spring Cache (CaffeineCache) entries that expire by themselves.
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("tools.jackson.core:jackson-databind")
    // YAML writer of the driver registration file (the same Jackson 3 mapper family, version from the Boot BOM).
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml")

    testImplementation(testFixtures(project(":ledger-domain")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // MockMvc and @AutoConfigureMockMvc (Boot 4 keeps the MVC test support in its own starter).
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    // Spring Security test support: the security filter chain in MockMvc.
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    // ArchUnit 1.5.1: not in the Boot BOM, see gradle/libs.versions.toml
    testImplementation(libs.archunit)
    // The test database is the embedded H2 in its default mode (a driver on the test classpath only; the product ships none).
    testImplementation("com.h2database:h2")
}

tasks.test {
    // The Host allow list tests send a Host header that differs from the address they connect to.
    jvmArgs("-Djdk.httpclient.allowRestrictedHeaders=host")
}

tasks.bootJar {
    archiveFileName = "ledger-admin-web.jar"
}
