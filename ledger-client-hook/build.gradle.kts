// One executable jar for the three client hooks: `java -jar ledger-client-hook.jar <claude|codex|antigravity>`, and for
// the key command `java -jar ledger-client-hook.jar session-key <author>`.
// Spring is not used here; the Spring Boot plugin (bootJar and its loader) is deliberately absent (HK-018).

val hookJarName = "ledger-client-hook.jar"
val hookMainClass = "org.pgledger.hook.Main"

dependencies {
    // Jackson 3 (tools.jackson), version from the Boot BOM; the only third-party runtime dependency (the JDK has no JSON parser).
    implementation("tools.jackson.core:jackson-databind")

    // The attestation token and the Ed25519 key text forms (pure JDK): the hook signs with them, the server verifies
    // with the same classes. The jar task below merges this module's classes into ledger-client-hook.jar.
    implementation(project(":ledger-attestation"))

    // ArchUnit 1.5.1: not in the Boot BOM, see gradle/libs.versions.toml; checks the no-network/no-file rules of HK-018.
    testImplementation(libs.archunit)
    // The shared architecture and source rules (ARC-033: no operating system detection) live in the domain test fixtures.
    // Test classpath only: the hook jar is built from the runtime classpath and stays free of the domain module.
    testImplementation(testFixtures(project(":ledger-domain")))
    // The contract vectors (author format, tool names) that the server tests against as well: the hook and the server are
    // two programs that each define those, so one set of files keeps them equal. Test classpath only.
    testImplementation(testFixtures(project(":ledger-attestation")))
}

tasks.jar {
    archiveFileName = hookJarName
    manifest {
        attributes("Main-Class" to hookMainClass)
        // jackson-core ships Java 17 specific classes under META-INF/versions
        attributes("Multi-Release" to "true")
    }
    // Standard `jar` task with the dependencies merged in: classes of the runtime classpath without signature files,
    // module descriptors and Maven metadata. Own classes come first, so they win a name clash. The licence and
    // notice texts of each dependency are kept under META-INF/third-party/<jar> (they would clash at META-INF/).
    // the dependency jars (Jackson and the project jar of ledger-attestation) are built before they are merged
    dependsOn(configurations.runtimeClasspath)
    val licenseFiles = listOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*-LICENSE")
    configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.forEach { dependency ->
        from(zipTree(dependency)) { exclude(licenseFiles) }
        from(zipTree(dependency)) {
            include(licenseFiles)
            eachFile { path = "META-INF/third-party/${dependency.nameWithoutExtension}/$name" }
            includeEmptyDirs = false
        }
    }
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/maven/**", "**/module-info.class")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Plugin directories for the three hosts: manifests and README of clients/<client> plus lib/<jar>.
// build/client-plugins/<client> is what the installation copies to the host's plugin location.
val stagedClients = listOf("claude", "codex", "antigravity")
val stageTasks = stagedClients.map { client ->
    tasks.register<Copy>("stage${client.replaceFirstChar { it.uppercase() }}Plugin") {
        group = "distribution"
        description = "Stages the $client plugin directory with lib/$hookJarName."
        from(rootProject.layout.projectDirectory.dir("clients/$client"))
        from(tasks.jar) { into("lib") }
        into(layout.buildDirectory.dir("client-plugins/$client"))
        includeEmptyDirs = false
    }
}
val stageClientPlugins by tasks.registering {
    group = "distribution"
    description = "Stages all three client plugin directories."
    dependsOn(stageTasks)
}
tasks.assemble { dependsOn(stageClientPlugins) }

tasks.test {
    dependsOn(tasks.jar, stageClientPlugins)
    // The tests start the real jar and read the manifests; the paths are passed in, never guessed.
    systemProperty("ledger.hook.jar", tasks.jar.flatMap { it.archiveFile }.get().asFile.absolutePath)
    systemProperty("ledger.hook.staging", layout.buildDirectory.dir("client-plugins").get().asFile.absolutePath)
    systemProperty("ledger.hook.metrics", layout.buildDirectory.dir("hook-metrics").get().asFile.absolutePath)
    systemProperty("ledger.hook.clients", rootProject.layout.projectDirectory.dir("clients").asFile.absolutePath)
}
