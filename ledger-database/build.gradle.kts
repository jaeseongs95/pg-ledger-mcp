dependencies {
    api(project(":ledger-domain"))
    // Spring JDBC, Spring Data JDBC and Hikari. No JDBC driver: the driver is registered by the user at run time.
    api("org.springframework.boot:spring-boot-starter-data-jdbc")
    // Bean Validation for the @Validated settings records: a bad value stops the startup (version from the Boot BOM)
    api("org.springframework.boot:spring-boot-starter-validation")

    testImplementation(testFixtures(project(":ledger-domain")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // JSON test data is written as records and serialised with Jackson 3 (Boot BOM)
    testImplementation("tools.jackson.core:jackson-databind")
    // ArchUnit: not in the Boot BOM, see gradle/libs.versions.toml
    testImplementation(libs.archunit)
    // The tests run on the embedded H2 only (default mode, no compatibility mode); the real DBMS is verified at the
    // test server stage. The driver is test-only: the product ships none.
    testImplementation("com.h2database:h2")
}
