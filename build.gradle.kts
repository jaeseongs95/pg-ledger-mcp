plugins {
    base
}

subprojects {
    apply(plugin = "java-library")

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
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release = 25
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Werror", "-Xlint:all", "-parameters"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // All time is UTC: the JVM default zone is pinned for the tests as well.
        jvmArgs("-Duser.timezone=UTC", "-Dfile.encoding=UTF-8", "-XX:ActiveProcessorCount=1")
        maxParallelForks = 1
        testLogging {
            events("passed", "failed", "skipped")
            showStandardStreams = false
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

// ARC-005 / DRV-014: no JDBC driver, JPA, migration tool, Lombok or CLI framework on any runtime classpath.
val forbiddenRuntime = listOf(
    "org.postgresql", "com.h2database", "com.mysql", "org.mariadb.jdbc", "com.oracle.database.jdbc",
    "com.microsoft.sqlserver", "org.liquibase", "org.flywaydb", "org.hibernate", "jakarta.persistence",
    "org.projectlombok", "info.picocli", "org.springframework.shell", "org.zeroturnaround",
)
val verifyRuntimeClasspath by tasks.registering {
    group = "verification"
    description = "Fails when a forbidden artifact is on the runtime classpath of a module."
    doLast {
        val problems = mutableListOf<String>()
        subprojects.forEach { p ->
            p.configurations.getByName("runtimeClasspath").resolvedConfiguration.resolvedArtifacts.forEach { a ->
                val group = a.moduleVersion.id.group
                if (forbiddenRuntime.any { group == it || group.startsWith("$it.") }) {
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
