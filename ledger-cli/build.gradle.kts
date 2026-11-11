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
    // Jimfs 1.3.2 (latest GA, not in the Boot BOM): a pure Java in-memory file system with POSIX modes and owners, so the
    // permission, owner and atomic-replace tests run on every OS without a shell or a second machine.
    testImplementation("com.google.jimfs:jimfs:1.3.2")
}

tasks.bootJar {
    archiveFileName = "ledger-cli.jar"
}
