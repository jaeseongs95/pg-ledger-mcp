dependencies {
    api(project(":ledger-domain"))
    // Spring JDBC, Spring Data JDBC and Hikari. No JDBC driver: the driver is registered by the user at run time.
    api("org.springframework.boot:spring-boot-starter-data-jdbc")

    testImplementation(testFixtures(project(":ledger-domain")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // JSON test data is written as records and serialised with Jackson 3 (Boot BOM)
    testImplementation("tools.jackson.core:jackson-databind")
    // zonky and ArchUnit: not in the Boot BOM, see gradle/libs.versions.toml
    testImplementation(libs.embedded.postgres)
    testImplementation(libs.archunit)
    // Drivers are test-only: H2 in default mode and PostgreSQL.
    testImplementation("com.h2database:h2")
    testRuntimeOnly("org.postgresql:postgresql")
    // zonky picks the binary archive of the running platform from the classpath (DefaultPostgresBinaryResolver: postgres-<OS>-<arch>.txz),
    // so the build does not decide by operating system; both archives are on the test runtime classpath.
    testRuntimeOnly(libs.embedded.postgres.binaries.windows.amd64)
    testRuntimeOnly(libs.embedded.postgres.binaries.linux.amd64)
}
