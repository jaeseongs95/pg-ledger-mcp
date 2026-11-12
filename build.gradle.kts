plugins {
    base
}

// Reads an optional Gradle project property (-P or gradle.properties); a malformed value fails the configuration with a clear message.
fun ledgerTestProperty(name: String): String? = providers.gradleProperty(name).orNull

fun positiveInt(name: String, value: String): Int {
    val number = value.toIntOrNull()
    require(number != null && number >= 1) { "$name must be an integer of at least 1, but is '$value'" }
    return number
}

// The settings of the test database (property name -> default). The defaults of the two password files are the files of the
// test PC; only the paths are here, the files are read by the test JVM and nothing else.
val ledgerTestDatabase = linkedMapOf(
    "ledger.test.jdbc-url" to "jdbc:postgresql://127.0.0.1:55432/ledger_test",
    "ledger.test.jdbc-user" to "ledger_test",
    "ledger.test.app-user" to "ledger_test_app",
    "ledger.test.jdbc-password-file" to "C:/ags-test-secrets/ags-dbms-ledger_test.pw",
    "ledger.test.app-password-file" to "C:/ags-test-secrets/ags-dbms-ledger_test_app.pw",
)

subprojects {
    apply(plugin = "java-library")

    // The PostgreSQL JDBC driver jar, for the tests that register a driver jar by its path (the test selects the one jar).
    // Test-only: no runtime classpath of any module has a driver (verifyRuntimeClasspath).
    val postgresDriverJar by configurations.creating {
        isCanBeConsumed = false
        isCanBeResolved = true
    }

    group = "org.pgledger"
    version = "0.1.0-SNAPSHOT"

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    dependencies {
        // The Boot BOM pins every managed version; the Spring AI BOM is declared for the later MCP modules.
        "implementation"(platform(rootProject.libs.spring.boot.bom))
        "implementation"(platform(rootProject.libs.spring.ai.bom))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testImplementation"("org.assertj:assertj-core")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
        "postgresDriverJar"(platform(rootProject.libs.spring.boot.bom))
        "postgresDriverJar"("org.postgresql:postgresql")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release = 25
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Werror", "-Xlint:all", "-parameters"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // All time is UTC: the JVM default zone is pinned for the tests as well.
        jvmArgs("-Duser.timezone=UTC", "-Dfile.encoding=UTF-8")
        // Optional limits of the test JVMs for machines with few resources. Without a property nothing is limited
        // (Gradle defaults). Pass them on the command line, e.g.
        // -Pledger.test.forks=1 -Pledger.test.maxHeapSize=1g -Pledger.test.activeProcessorCount=1
        ledgerTestProperty("ledger.test.forks")?.let { maxParallelForks = positiveInt("ledger.test.forks", it) }
        ledgerTestProperty("ledger.test.maxHeapSize")?.let {
            require(Regex("[1-9][0-9]*[kKmMgG]?").matches(it)) {
                "ledger.test.maxHeapSize must be a size such as 512m or 1g, but is '$it'"
            }
            maxHeapSize = it
        }
        ledgerTestProperty("ledger.test.activeProcessorCount")?.let {
            jvmArgs("-XX:ActiveProcessorCount=${positiveInt("ledger.test.activeProcessorCount", it)}")
        }
        // The test database is the PostgreSQL of the test PC (docs/testing/TEST_CASES.md 1.4a, 1.4b). Each setting is a Gradle
        // property with a default and reaches the test JVM as a system property. The two password file properties carry
        // the PATH of a one-line file only: the test support reads the file, and the value is never a property, a log
        // line or a message. A test that cannot reach the database fails; it is not skipped.
        ledgerTestDatabase.forEach { (name, fallback) ->
            systemProperty(name, ledgerTestProperty(name) ?: fallback)
        }
        // The path of the PostgreSQL JDBC driver jar (test-only configuration), for the tests that register a driver.
        doFirst {
            // the version comes from the Boot BOM (a platform needs the transitive resolution); the driver jar is the file
            // named postgresql-<version>.jar among the resolved files
            systemProperty("ledger.test.postgres-jar",
                postgresDriverJar.files.single { it.name.startsWith("postgresql-") }.absolutePath)
        }
        // A test of a database is never up to date: the state of the database is an input that Gradle cannot see.
        if (project.name in setOf("ledger-database", "ledger-application", "ledger-mcp-server", "ledger-admin-web",
                "ledger-cli", "ledger-system-test")) {
            outputs.upToDateWhen { false }
        }
        testLogging {
            events("passed", "failed", "skipped")
            showStandardStreams = false
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

// ARC-005 / DRV-014: no JDBC driver, JPA, migration tool, Lombok or CLI framework on any runtime classpath.
// "org.hibernate.orm" (not "org.hibernate"): the Jakarta Validation implementation is org.hibernate.validator, which is allowed; the ORM stays forbidden.
val forbiddenRuntime = listOf(
    "org.postgresql", "com.h2database", "com.mysql", "org.mariadb.jdbc", "com.oracle.database.jdbc",
    "com.microsoft.sqlserver", "org.liquibase", "org.flywaydb", "org.hibernate.orm", "jakarta.persistence",
    "org.projectlombok", "info.picocli", "org.springframework.shell", "org.zeroturnaround",
)
// The one exception (DRV-014, SETUP): the H2 driver, on ledger-seed and on ledger-cli (which uses it) only. H2 gives the format of
// the seed bundle, the initial data of a new installation that `ledger-cli setup` moves into the ledger database, and nothing
// else: the ledger database is always a DBMS the operator installs (user decision 「dbms 설치가 전제라니까?」, 「h2는 그런목적으로만
// 쓰라고.」), the product refuses to register H2 as it, and the MCP server and the admin web keep zero JDBC drivers. The system test
// (DRV-013) looks for the driver in the built jars of the three applications.
val embeddedDriverGroup = "com.h2database"
val embeddedDriverModules = setOf("ledger-seed", "ledger-cli")
val verifyRuntimeClasspath by tasks.registering {
    group = "verification"
    description = "Fails when a forbidden artifact is on the runtime classpath of a module."
    doLast {
        val problems = mutableListOf<String>()
        subprojects.forEach { p ->
            p.configurations.getByName("runtimeClasspath").resolvedConfiguration.resolvedArtifacts.forEach { a ->
                val group = a.moduleVersion.id.group
                val seedReader = group == embeddedDriverGroup && p.name in embeddedDriverModules
                if (!seedReader && forbiddenRuntime.any { group == it || group.startsWith("$it.") }) {
                    problems += "${p.name}: ${a.moduleVersion.id}"
                }
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("forbidden runtime dependencies: $problems")
        }
    }
}
tasks.named("check") { dependsOn(verifyRuntimeClasspath) }
