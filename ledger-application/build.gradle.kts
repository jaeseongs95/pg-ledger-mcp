dependencies {
    api(project(":ledger-database"))
    // Jackson 3 (Boot BOM): history changes, before_data and receipt responses are records written as JSON text.
    implementation("tools.jackson.core:jackson-databind")

    testImplementation(testFixtures(project(":ledger-domain")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // zonky and ArchUnit: not in the Boot BOM, see gradle/libs.versions.toml
    testImplementation(libs.embedded.postgres)
    testImplementation(libs.archunit)
    // Drivers are test-only: H2 in default mode and PostgreSQL.
    testImplementation("com.h2database:h2")
    testRuntimeOnly("org.postgresql:postgresql")
    // Both platform binaries are on the test classpath; the embedded server loads the one of the machine it runs on.
    testRuntimeOnly(libs.embedded.postgres.binaries.windows.amd64)
    testRuntimeOnly(libs.embedded.postgres.binaries.linux.amd64)
}
