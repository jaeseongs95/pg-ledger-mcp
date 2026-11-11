plugins {
    // Spring Boot Gradle plugin: bootJar builds the one executable jar (java -jar ledger-cli.jar); the version is the catalog Boot version.
    id("org.springframework.boot") version libs.versions.springBoot.get()
}

dependencies {
    // Plain starter only (Spring Boot core, logging, MessageSource): no JDBC, no DataSource auto-configuration, because
    // secret-set and driver-register must run before any JDBC driver exists on this machine.
    implementation("org.springframework.boot:spring-boot-starter")
    // Bean Validation for the @Validated settings records: a bad value stops the startup (version from the Boot BOM)
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Reads and fingerprints the public key of a session key (JDK only, shared with the hook and the server).
    implementation(project(":ledger-attestation"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // ArchUnit 1.5.1: see gradle/libs.versions.toml
    testImplementation(libs.archunit)
    // The shared architecture and source rules (ARC-033: no operating system detection) live in the domain test fixtures.
    testImplementation(testFixtures(project(":ledger-domain")))
}

tasks.bootJar {
    archiveFileName = "ledger-cli.jar"
}
