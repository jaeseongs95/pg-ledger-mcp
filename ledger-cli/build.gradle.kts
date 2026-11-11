plugins {
    application
}

dependencies {
    // Plain starter only (Spring Boot core, logging, MessageSource): no JDBC, no DataSource auto-configuration, because
    // secret-set and driver-register must run before any JDBC driver exists on this machine.
    implementation("org.springframework.boot:spring-boot-starter")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // ArchUnit 1.5.1: see gradle/libs.versions.toml
    testImplementation(libs.archunit)
}

application {
    mainClass = "org.pgledger.cli.LedgerCliApplication"
    applicationName = "ledger-cli"
}
