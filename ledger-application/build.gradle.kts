dependencies {
    api(project(":ledger-database"))
    // The signed author attestation (token format and Ed25519 primitives, JDK only), shared with the client hook.
    implementation(project(":ledger-attestation"))
    // Jakarta Validation (Hibernate Validator, Boot BOM): the service input records carry the constraints; the
    // records are public API of this module, so the consumers need the annotations on their compile classpath.
    api("org.springframework.boot:spring-boot-starter-validation")
    // Jackson 3 (Boot BOM): history changes, before_data and receipt responses are records written as JSON text.
    // Spring Boot's starter makes the JsonMapper bean that LedgerJson derives its mappers from.
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("tools.jackson.core:jackson-databind")

    testImplementation(testFixtures(project(":ledger-domain")))
    // The builder of the legacy ledger on H2 (the table shapes of the old schema and the data of the old installer), MIG-024.
    testImplementation(testFixtures(project(":ledger-database")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // ArchUnit 1.5.1: not in the Boot BOM, see gradle/libs.versions.toml
    testImplementation(libs.archunit)
    // The test database is the embedded H2 in its default mode (a driver on the test classpath only).
    testImplementation("com.h2database:h2")
}
