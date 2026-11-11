plugins {
    // Spring Boot Gradle plugin: bootJar builds the one executable jar (java -jar ledger-cli.jar); the version is the catalog Boot version.
    id("org.springframework.boot") version libs.versions.springBoot.get()
}

dependencies {
    // Plain starter only (Spring Boot core, logging, MessageSource): no JDBC, no DataSource auto-configuration, because
    // secret-set and driver-register must run before any JDBC driver exists on this machine.
    implementation("org.springframework.boot:spring-boot-starter")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // ArchUnit 1.5.1: see gradle/libs.versions.toml
    testImplementation(libs.archunit)
}

tasks.bootJar {
    archiveFileName = "ledger-cli.jar"
}
