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
    // The test database support (the PostgreSQL of the test PC: settings, pools, schema per test, PostgreSQL driver) and the
    // builder of the legacy ledger (the table shapes of the old schema and the data of the old installer), MIG-024.
    testImplementation(testFixtures(project(":ledger-database")))
}

tasks.bootJar {
    archiveFileName = "ledger-cli.jar"
}

// DOC-001 .. DOC-029: the documentation tests read the READMEs, the release notes and the sources of the other modules as
// text. The root of the repository is passed in, never guessed, and the files are inputs, so a change of a document or of
// the code a document mirrors runs the tests again.
tasks.test {
    systemProperty("ledger.docs.root", rootProject.layout.projectDirectory.asFile.absolutePath)
    inputs.files(rootProject.fileTree(rootProject.layout.projectDirectory) {
        include("README*.md", "docs/**/*.md", "clients/**", "settings.gradle.kts", "build.gradle.kts",
            "ledger-*/src/main/java/**/*.java")
    }).withPropertyName("documentationSources")
}

// mcp-check is tested against an MCP server started in the test: the MCP Java SDK server over Streamable HTTP in an embedded
// Tomcat. Tomcat brings the servlet classes, and the tests of the CLI itself assert that the CLI has none (CLI-002), so these
// tests have a source set and a test task of their own: it sees what the tests see (the test classes and their dependencies)
// and adds Tomcat. Boot excludes tomcat-annotations-api and uses the Jakarta annotations API instead; so does this.
val serverTest: SourceSet = sourceSets.create("serverTest") {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output + configurations.testCompileClasspath.get()
    runtimeClasspath += output + compileClasspath + configurations.testRuntimeClasspath.get()
}
configurations["serverTestImplementation"].extendsFrom(configurations.testImplementation.get())
configurations["serverTestRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get())
dependencies {
    "serverTestImplementation"("org.apache.tomcat.embed:tomcat-embed-core") {
        exclude(group = "org.apache.tomcat", module = "tomcat-annotations-api")
    }
    "serverTestImplementation"("jakarta.annotation:jakarta.annotation-api")
}
val serverTestTask = tasks.register<Test>("serverTest") {
    description = "Runs the tests that need an MCP server (embedded Tomcat)."
    group = "verification"
    testClassesDirs = serverTest.output.classesDirs
    classpath = serverTest.runtimeClasspath
    shouldRunAfter(tasks.test)
}
tasks.named("check") { dependsOn(serverTestTask) }
