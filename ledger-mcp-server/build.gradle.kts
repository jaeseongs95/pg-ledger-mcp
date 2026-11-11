plugins {
    // Spring Boot Gradle plugin: bootJar builds the one executable jar (java -jar ledger-mcp-server.jar); the version is the catalog Boot version.
    id("org.springframework.boot") version libs.versions.springBoot.get()
}

dependencies {
    // The services of the ledger (write gate, receipts, history, queries) with the database module and the domain.
    implementation(project(":ledger-application"))
    // Spring AI MCP server over Streamable HTTP on Spring MVC (version from the Spring AI BOM declared at the root).
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    // Bearer token and Host allow list in one Spring Security filter chain.
    implementation("org.springframework.boot:spring-boot-starter-security")
    // The bearer token filter of Spring Security (BearerTokenAuthenticationFilter and its token resolver); no JWT is used.
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    // /healthz is the Actuator health endpoint.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("tools.jackson.core:jackson-databind")

    testImplementation(testFixtures(project(":ledger-domain")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // ArchUnit 1.5.1: not in the Boot BOM, see gradle/libs.versions.toml
    testImplementation(libs.archunit)
    // The test database is the embedded H2 in its default mode (a driver on the test classpath only; the product ships none).
    testImplementation("com.h2database:h2")
}

tasks.test {
    // The JDK HttpClient refuses to send a Host header unless the property allows it. The Host allow list test
    // (MCC-016) must send a Host that differs from the address it connects to.
    jvmArgs("-Djdk.httpclient.allowRestrictedHeaders=host")
}

tasks.bootJar {
    archiveFileName = "ledger-mcp-server.jar"
}
