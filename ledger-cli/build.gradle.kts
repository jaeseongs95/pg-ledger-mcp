plugins {
    // Spring Boot Gradle plugin: bootJar builds the one executable jar (java -jar ledger-cli.jar); the version is the catalog Boot version.
    id("org.springframework.boot") version libs.versions.springBoot.get()
}

dependencies {
    // Spring Boot core, logging, MessageSource. The database beans exist only when a JDBC driver is registered (see
    // org.pgledger.cli.database), because secret-set and driver-register must run before any JDBC driver exists on this
    // machine; the application excludes DataSourceAutoConfiguration, the DataSource comes from the registered driver.
    implementation("org.springframework.boot:spring-boot-starter")
    // The ledger services (key registration ...) with the database module (Spring Data JDBC, Hikari; no JDBC driver) and the
    // domain: the same modules the admin web uses for its console.
    implementation(project(":ledger-application"))
    // Bean Validation for the @Validated settings records: a bad value stops the startup (version from the Boot BOM)
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Reads and fingerprints the public key of a session key (JDK only, shared with the hook and the server).
    implementation(project(":ledger-attestation"))
    // The MCP Java SDK client of mcp-check (the SDK the server runs on) and the Jackson 3 mapper it writes JSON with (Boot BOM).
    implementation(libs.mcp.sdk)
    implementation("tools.jackson.core:jackson-databind")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // ArchUnit 1.5.1: see gradle/libs.versions.toml
    testImplementation(libs.archunit)
    // The shared architecture and source rules (ARC-033: no operating system detection) live in the domain test fixtures.
    testImplementation(testFixtures(project(":ledger-domain")))
    // The test database is the embedded H2 in its default mode (a driver on the test classpath only; the product ships none).
    testImplementation("com.h2database:h2")
    // mcp-check is tested against an MCP server started in the test: the MCP Java SDK server over Streamable HTTP in an embedded Tomcat
    // (a library only: no Spring web auto-configuration reaches the CLI context). Boot excludes tomcat-annotations-api and uses the
    // Jakarta annotations API instead; so does this.
    testImplementation("org.apache.tomcat.embed:tomcat-embed-core") {
        exclude(group = "org.apache.tomcat", module = "tomcat-annotations-api")
    }
    testImplementation("jakarta.annotation:jakarta.annotation-api")
}

tasks.bootJar {
    archiveFileName = "ledger-cli.jar"
}
