plugins {
    `java-test-fixtures`
}

dependencies {
    api(project(":ledger-domain"))
    // Spring JDBC, Spring Data JDBC and Hikari. No JDBC driver: the driver is registered by the user at run time.
    api("org.springframework.boot:spring-boot-starter-data-jdbc")
    // Bean Validation for the @Validated settings records: a bad value stops the startup (version from the Boot BOM)
    api("org.springframework.boot:spring-boot-starter-validation")
    // YAML reader and writer (Jackson 3, Boot BOM) of the driver registration file that the admin web and the CLI write.
    api("tools.jackson.dataformat:jackson-dataformat-yaml")

    testImplementation(testFixtures(project(":ledger-domain")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // The test slices of Spring Boot 4 are modules of their own (version from the Boot BOM): @JdbcTest and @DataJdbcTest
    // for the repository tests (principle 7.2), on the PostgreSQL of the test PC (see the test fixtures below).
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jdbc-test")
    // JSON test data is written as records and serialised with Jackson 3 (Boot BOM)
    testImplementation("tools.jackson.core:jackson-databind")
    // ArchUnit: not in the Boot BOM, see gradle/libs.versions.toml
    testImplementation(libs.archunit)
}

// The test database support (TEST_CASES.md 1.4a): the settings, the connection pools and the schema per test on the PostgreSQL of
// the test PC, and the builder of the legacy ledger for the tests (MIG-024): the table shapes of the old schema on that
// PostgreSQL (in a schema of its own) and the data of the old installer, shared by the tests of the import in the application
// and CLI modules. H2 is not a test database. The PostgreSQL driver is a test fixture dependency and so is on the test class
// path of every module that uses the fixtures; the product ships none, and the runtime class path check does not look at
// test fixtures.
dependencies {
    testFixturesImplementation(platform(libs.spring.boot.bom))
    testFixturesApi("org.postgresql:postgresql")
    testFixturesImplementation("tools.jackson.core:jackson-databind")
}
